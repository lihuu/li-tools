package io.github.lihuu.mitmkit.core;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A regular HTTP/HTTPS proxy that terminates client TLS with per-host leaf certificates
 * and records every flow. One virtual thread handles each client connection.
 */
public final class ProxyServer {
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final int MAX_HEADER_BYTES = 64 * 1024;

    private final String host;
    private final int requestedPort;
    private final CertAuthority authority;
    private final FlowRecorder recorder;
    private final boolean insecureUpstream;
    private final AtomicLong flowIds = new AtomicLong();
    private final List<Socket> clientSockets = new CopyOnWriteArrayList<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final boolean recordHeaders;
    private final boolean recordBody;
    private final boolean trustLocal;
    private final List<RewriteRule> rewriteRules;

    private ServerSocket serverSocket;
    private int port;

    public ProxyServer(@NotNull String host, int requestedPort, @NotNull CertAuthority authority,
                       @NotNull FlowRecorder recorder, boolean insecureUpstream) {
        this(host, requestedPort, authority, recorder, insecureUpstream, false, false, false, List.of());
    }

    public ProxyServer(@NotNull String host, int requestedPort, @NotNull CertAuthority authority,
                       @NotNull FlowRecorder recorder, boolean insecureUpstream,
                       boolean recordHeaders, boolean recordBody, boolean trustLocal,
                       @NotNull List<RewriteRule> rewriteRules) {
        this.host = host;
        this.requestedPort = requestedPort;
        this.authority = authority;
        this.recorder = recorder;
        this.insecureUpstream = insecureUpstream;
        this.recordHeaders = recordHeaders;
        this.recordBody = recordBody;
        this.trustLocal = trustLocal;
        this.rewriteRules = rewriteRules;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.bind(new InetSocketAddress(host, requestedPort));
        port = serverSocket.getLocalPort();
        executor.execute(this::acceptLoop);
    }

    public int port() {
        return port;
    }

    public void stop() {
        try {
            serverSocket.close();
        } catch (IOException ignored) {
        }
        for (Socket socket : clientSockets) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
        executor.shutdownNow();
    }

    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            try {
                Socket client = serverSocket.accept();
                clientSockets.add(client);
                executor.execute(() -> handleClient(client));
            } catch (IOException failure) {
                return;
            }
        }
    }

    private void handleClient(@NotNull Socket client) {
        try (Socket ignored = client) {
            client.setSoTimeout(READ_TIMEOUT_MS);
            InputStream input = client.getInputStream();
            RequestHead head = readRequestHead(input);
            if (head == null) return;
            if ("CONNECT".equalsIgnoreCase(head.method())) {
                handleConnect(client, head);
            } else {
                handlePlainHttp(client, head);
            }
        } catch (Exception failure) {
            recordError("CONNECT".equals("") ? "?" : headMethodSafe(failure), "unknown", failure);
        } finally {
            clientSockets.remove(client);
        }
    }

    private void handleConnect(@NotNull Socket client, @NotNull RequestHead head) throws Exception {
        String target = head.url();
        String hostPort = target.contains(":") ? target : target + ":443";
        String upstreamHost = hostPort.substring(0, hostPort.lastIndexOf(':'));
        int upstreamPort = Integer.parseInt(hostPort.substring(hostPort.lastIndexOf(':') + 1));

        // Tell the client the tunnel is established, then terminate TLS with a per-host leaf cert.
        OutputStream clientOut = client.getOutputStream();
        clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        clientOut.flush();

        SSLEngine engine = serverEngine(upstreamHost);
        TlsTunnel tunnel = new TlsTunnel(client, engine);
        tunnel.handshake();

        InputStream tlsInput = tunnel.inputStream();
        RequestHead inner = readRequestHead(tlsInput);
        if (inner == null) return;
        exchange(tunnel, inner, "https://" + hostPort + inner.url(), upstreamHost, upstreamPort);
    }

    private void handlePlainHttp(@NotNull Socket client, @NotNull RequestHead head) throws Exception {
        URI uri = URI.create(head.url());
        String upstreamHost = uri.getHost();
        int upstreamPort = uri.getPort() > 0 ? uri.getPort() : 80;
        exchange(new PlainTunnel(client), head, head.url(), upstreamHost, upstreamPort);
    }

    private void exchange(@NotNull Tunnel tunnel, @NotNull RequestHead head, @NotNull String fullUrl,
                          @NotNull String upstreamHost, int upstreamPort) throws Exception {
        long id = flowIds.incrementAndGet();
        long start = System.currentTimeMillis();
        byte[] body = readBody(head, tunnel.inputStream());

        RewriteContext rewrite = new RewriteContext();
        String effectiveUrl = rewrite.applyRequest(fullUrl, head.method());
        if (!effectiveUrl.equals(fullUrl)) {
            fullUrl = effectiveUrl;
            URI uri = URI.create(fullUrl);
            upstreamHost = uri.getHost();
            upstreamPort = uri.getPort() > 0 ? uri.getPort() : upstreamPort;
        }
        body = rewrite.applyRequestBody(head.method(), fullUrl, body);
        byte[] effectiveBody = body;

        try (Socket upstream = openUpstream(upstreamHost, upstreamPort, uriScheme(head, fullUrl))) {
            writeRequest(upstream.getOutputStream(), head, effectiveBody, fullUrl, head.url(), upstreamHost, upstreamPort, rewrite);
            ResponseHead response = readResponseHead(upstream.getInputStream());
            byte[] responseBody = readBody(response, upstream.getInputStream());
            responseBody = rewrite.applyResponse(head.method(), fullUrl, responseBody);

            writeResponse(tunnel.outputStream(), response, responseBody, head.method(), head.url(), rewrite);

            long duration = System.currentTimeMillis() - start;
            recorder.record(new FlowRecorder.Flow(id, head.method(), fullUrl,
                    response.statusCode(), duration, effectiveBody.length, responseBody.length,
                    recordHeaders ? head.headers() : null,
                    recordHeaders ? response.headers() : null,
                    recordBody ? decodeText(effectiveBody) : null,
                    recordBody ? decodeText(responseBody) : null,
                    null));
        } catch (Exception failure) {
            long duration = System.currentTimeMillis() - start;
            recorder.record(new FlowRecorder.Flow(id, head.method(), fullUrl, null, duration,
                    effectiveBody.length, 0, null, null, null, null, message(failure)));
            try {
                byte[] errorBody = message(failure).getBytes(StandardCharsets.UTF_8);
                writeResponse(tunnel.outputStream(),
                        new ResponseHead(502, "Bad Gateway", Map.of()), errorBody,
                        head.method(), head.url(), new RewriteContext());
            } catch (IOException ignored) {
                // The client may already have gone away.
            }
        }
    }

    /** Applies matching rewrite rules to requests and responses for one exchange. */
    private final class RewriteContext {
        private final java.util.List<RewriteRule> rules;

        RewriteContext() {
            this.rules = ProxyServer.this.rewriteRules;
        }

        @NotNull String applyRequest(@NotNull String url, @NotNull String method) {
            String result = url;
            for (RewriteRule rule : rules) {
                if (!rule.matches(method, url)) continue;
                if (rule.newUrl() != null) {
                    result = rule.applyUrl(result);
                }
            }
            return result;
        }

        byte[] applyRequestBody(@NotNull String method, @NotNull String url, byte[] body) {
            byte[] result = body;
            for (RewriteRule rule : rules) {
                if (!rule.matches(method, url)) continue;
                if (rule.replacesRequestBody()) {
                    result = rule.requestBody(result);
                }
            }
            return result;
        }

        byte[] applyResponse(@NotNull String method, @NotNull String url, byte[] body) {
            byte[] result = body;
            for (RewriteRule rule : rules) {
                if (!rule.matches(method, url)) continue;
                if (rule.replacesResponseBody()) {
                    result = rule.responseBody(result);
                }
            }
            return result;
        }

        /**
         * Returns a new header map with the matching rules applied: headers are
         * appended when absent and replaced when present, cookies are merged the
         * same way.
         */
        @NotNull Map<String, List<String>> applyRequestHeaders(
                @NotNull String method, @NotNull String url,
                @NotNull Map<String, List<String>> original) {
            Map<String, List<String>> headers = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            headers.putAll(original);
            for (RewriteRule rule : rules) {
                if (!rule.matches(method, url)) continue;
                applyHeader(headers, rule.requestHeaderValue());
                applyCookie(headers, rule.cookieValue());
            }
            return headers;
        }

        @NotNull Map<String, List<String>> applyResponseHeaders(
                @NotNull String method, @NotNull String url,
                @NotNull Map<String, List<String>> original) {
            Map<String, List<String>> headers = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            headers.putAll(original);
            for (RewriteRule rule : rules) {
                if (!rule.matches(method, url)) continue;
                applyHeader(headers, rule.responseHeaderValue());
            }
            return headers;
        }

        private static void applyHeader(@NotNull Map<String, List<String>> headers, @Nullable String header) {
            if (header == null) return;
            int colon = header.indexOf(':');
            if (colon <= 0) return;
            headers.put(header.substring(0, colon).trim(), List.of(header.substring(colon + 1).trim()));
        }

        private static void applyCookie(@NotNull Map<String, List<String>> headers, @Nullable String cookie) {
            if (cookie == null) return;
            int equals = cookie.indexOf('=');
            if (equals <= 0) return;
            String name = cookie.substring(0, equals).trim();
            String value = cookie.substring(equals + 1).trim();
            List<String> existing = headers.get("cookie");
            if (existing == null || existing.isEmpty()) {
                headers.put("cookie", List.of(name + "=" + value));
                return;
            }
            String merged = mergeCookie(existing.get(0), name, value);
            headers.put("cookie", List.of(merged));
        }

        private static @NotNull String mergeCookie(@NotNull String header, @NotNull String name,
                                                  @NotNull String value) {
            String[] pairs = header.split(";");
            StringBuilder builder = new StringBuilder();
            boolean replaced = false;
            for (String pair : pairs) {
                String trimmed = pair.trim();
                if (trimmed.isEmpty()) continue;
                if (builder.length() > 0) builder.append("; ");
                if (trimmed.startsWith(name + "=")) {
                    builder.append(name).append('=').append(value);
                    replaced = true;
                } else {
                    builder.append(trimmed);
                }
            }
            if (!replaced) {
                if (builder.length() > 0) builder.append("; ");
                builder.append(name).append('=').append(value);
            }
            return builder.toString();
        }
    }

    private @NotNull Socket openUpstream(@NotNull String host, int port, String scheme) throws Exception {
        if ("https".equals(scheme)) {
            boolean skipVerification = insecureUpstream || (trustLocal && isLoopback(host));
            SSLSocketFactory factory = skipVerification
                    ? insecureContext().getSocketFactory()
                    : SSLContext.getDefault().getSocketFactory();
            SSLSocket socket = (SSLSocket) factory.createSocket();
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.startHandshake();
            return socket;
        }
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        return socket;
    }

    private static boolean isLoopback(@NotNull String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                || "::1".equals(host) || "[::1]".equals(host);
    }

    private static @NotNull String requestTarget(@NotNull String url) {
        try {
            URI uri = URI.create(url);
            if (uri.getScheme() != null) {
                String path = uri.getRawPath();
                if (path == null || path.isEmpty()) path = "/";
                String query = uri.getRawQuery();
                return query == null ? path : path + "?" + query;
            }
            return url;
        } catch (IllegalArgumentException failure) {
            return url;
        }
    }

    private static @NotNull String uriScheme(@NotNull RequestHead head, @NotNull String fullUrl) {
        try {
            String scheme = URI.create(fullUrl).getScheme();
            return scheme == null ? "http" : scheme;
        } catch (IllegalArgumentException failure) {
            return "https";
        }
    }

    private @NotNull SSLEngine serverEngine(@NotNull String host) throws Exception {
        CertAuthority.Leaf leaf = authority.issue(host);
        char[] password = "changeit".toCharArray();
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, password);
        keyStore.setKeyEntry("leaf", leaf.key(), password,
                new java.security.cert.Certificate[]{leaf.certificate(), authority.caCertificate()});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, password);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), null, new SecureRandom());
        SSLEngine engine = context.createSSLEngine();
        engine.setUseClientMode(false);
        engine.setEnabledProtocols(new String[]{"TLSv1.2", "TLSv1.3"});
        return engine;
    }

    private static @NotNull SSLContext insecureContext() throws Exception {
        TrustManager[] trustAll = new TrustManager[]{new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}
            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}
            @Override
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }};
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustAll, new SecureRandom());
        return context;
    }

    private void writeRequest(@NotNull OutputStream out, @NotNull RequestHead head,
                              byte[] body, @NotNull String sendUrl, @NotNull String matchUrl,
                              @NotNull String host, int port,
                              @NotNull RewriteContext rewrite) throws IOException {
        Map<String, List<String>> headers = rewrite.applyRequestHeaders(
                head.method(), matchUrl, head.headers());
        StringBuilder builder = new StringBuilder();
        builder.append(head.method()).append(' ').append(requestTarget(sendUrl)).append(" HTTP/1.1\r\n");
        builder.append("Host: ").append(host);
        if (port != 80 && port != 443) builder.append(':').append(port);
        builder.append("\r\n");
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            String name = entry.getKey();
            if (name.equalsIgnoreCase("host") || name.equalsIgnoreCase("proxy-connection")) continue;
            for (String value : entry.getValue()) {
                builder.append(name).append(": ").append(value).append("\r\n");
            }
        }
        builder.append("Connection: close\r\n");
        if (body.length > 0) builder.append("Content-Length: ").append(body.length).append("\r\n");
        builder.append("\r\n");
        out.write(builder.toString().getBytes(StandardCharsets.UTF_8));
        if (body.length > 0) out.write(body);
        out.flush();
    }

    private void writeResponse(@NotNull OutputStream out, @NotNull ResponseHead response,
                               byte[] body, @NotNull String method, @NotNull String matchUrl,
                               @NotNull RewriteContext rewrite) throws IOException {
        Map<String, List<String>> headers = rewrite.applyResponseHeaders(method, matchUrl, response.headers());
        StringBuilder builder = new StringBuilder();
        builder.append("HTTP/1.1 ").append(response.statusCode()).append(' ')
                .append(response.reasonPhrase()).append("\r\n");
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            String name = entry.getKey();
            if (name.equalsIgnoreCase("transfer-encoding") || name.equalsIgnoreCase("connection")) continue;
            for (String value : entry.getValue()) {
                builder.append(name).append(": ").append(value).append("\r\n");
            }
        }
        builder.append("Content-Length: ").append(body.length).append("\r\n");
        builder.append("Connection: close\r\n\r\n");
        out.write(builder.toString().getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private static @NotNull RequestHead readRequestHead(@NotNull InputStream input) throws IOException {
        Headers headers = readHeaders(input);
        if (headers == null) return null;
        String requestLine = headers.requestLine();
        if (requestLine == null) return null;
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) return null;
        return new RequestHead(parts[0], parts[1], headers.headerMap());
    }

    private static @NotNull ResponseHead readResponseHead(@NotNull InputStream input) throws IOException {
        Headers headers = readHeaders(input);
        if (headers == null) return new ResponseHead(502, "Bad Gateway", Map.of());
        String statusLine = headers.requestLine();
        if (statusLine == null) return new ResponseHead(502, "Bad Gateway", Map.of());
        String[] parts = statusLine.split(" ", 3);
        int code = parts.length >= 2 ? parseStatusCode(parts[1]) : 502;
        String reason = parts.length >= 3 ? parts[2] : "";
        return new ResponseHead(code, reason, headers.headerMap());
    }

    private static byte[] readBody(@NotNull RequestHead head, @NotNull InputStream input) throws IOException {
        return new byte[0];
    }

    private static byte[] readBody(@NotNull ResponseHead head, @NotNull InputStream input) throws IOException {
        List<String> contentLength = head.headers().get("content-length");
        if (contentLength != null && !contentLength.isEmpty()) {
            int length = Integer.parseInt(contentLength.get(0).trim());
            return input.readNBytes(length);
        }
        return input.readAllBytes();
    }

    private static @Nullable Headers readHeaders(@NotNull InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int previous3 = -1;
        int previous2 = -1;
        int previous1 = -1;
        int current;
        int total = 0;
        while ((current = input.read()) != -1) {
            total++;
            if (total > MAX_HEADER_BYTES) throw new IOException("header section too large");
            if (previous3 == '\r' && previous2 == '\n' && previous1 == '\r' && current == '\n') {
                return parseHeaders(buffer.toByteArray());
            }
            buffer.write(current);
            previous3 = previous2;
            previous2 = previous1;
            previous1 = current;
        }
        return null;
    }

    private static @NotNull Headers parseHeaders(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        String[] lines = text.split("\r\n");
        String first = lines.length > 0 ? lines[0] : "";
        Map<String, List<String>> headerMap = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (int index = 1; index < lines.length; index++) {
            String line = lines[index];
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String name = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            headerMap.computeIfAbsent(name, key -> new java.util.ArrayList<>()).add(value);
        }
        return new Headers(first, headerMap);
    }

    private static int parseStatusCode(@NotNull String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException failure) {
            return 502;
        }
    }

    private static @NotNull String decodeText(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static @NotNull String message(@NotNull Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private static @NotNull String headMethodSafe(@NotNull Throwable failure) {
        return "?";
    }

    private void recordError(@NotNull String method, @NotNull String url, @NotNull Throwable failure) {
        recorder.record(new FlowRecorder.Flow(flowIds.incrementAndGet(), method, url, null, 0,
                0, 0, null, null, null, null, message(failure)));
    }

    private record RequestHead(@NotNull String method, @NotNull String url,
                               @NotNull Map<String, List<String>> headers) {}

    private record ResponseHead(int statusCode, @NotNull String reasonPhrase,
                                @NotNull Map<String, List<String>> headers) {}

    private record Headers(@Nullable String requestLine, @NotNull Map<String, List<String>> headerMap) {}

    /** A client-side tunnel: either a plain socket or a TLS-terminated SSLEngine session. */
    private interface Tunnel {
        @NotNull InputStream inputStream() throws IOException;
        @NotNull OutputStream outputStream() throws IOException;
    }

    private static final class PlainTunnel implements Tunnel {
        private final Socket socket;

        PlainTunnel(@NotNull Socket socket) {
            this.socket = socket;
        }

        @Override
        public @NotNull InputStream inputStream() throws IOException {
            return socket.getInputStream();
        }

        @Override
        public @NotNull OutputStream outputStream() throws IOException {
            return socket.getOutputStream();
        }
    }

    /**
     * Terminates client TLS with an {@link SSLEngine} over an existing socket, exposing the
     * decrypted stream for HTTP parsing. Buffers leftover plaintext so bytes already read
     * during the handshake are not lost.
     */
    private static final class TlsTunnel implements Tunnel {
        private final Socket socket;
        private final SSLEngine engine;
        private final ByteBuffer netRead;
        private ByteBuffer netWrite;
        private ByteBuffer appRead;
        private final ByteBuffer appWrite;
        private InputStream decryptedInput;

        TlsTunnel(@NotNull Socket socket, @NotNull SSLEngine engine) {
            this.socket = socket;
            this.engine = engine;
            SSLSession session = engine.getSession();
            this.netRead = ByteBuffer.allocate(session.getPacketBufferSize());
            this.netWrite = ByteBuffer.allocate(session.getPacketBufferSize());
            this.appRead = ByteBuffer.allocate(session.getApplicationBufferSize());
            this.appWrite = ByteBuffer.allocate(session.getApplicationBufferSize());
        }

        void handshake() throws IOException {
            engine.beginHandshake();
            netRead.limit(0);
            while (true) {
                SSLEngineResult.HandshakeStatus status = engine.getHandshakeStatus();
                switch (status) {
                    case NEED_TASK -> {
                        Runnable delegated = engine.getDelegatedTask();
                        while (delegated != null) {
                            delegated.run();
                            delegated = engine.getDelegatedTask();
                        }
                    }
                    case NEED_UNWRAP -> {
                        if (netRead.position() >= netRead.limit()) {
                            // No buffered ciphertext: read one record's worth.
                            netRead.clear();
                            int read = socket.getInputStream().read(netRead.array());
                            if (read < 0) throw new EOFException("client closed during handshake");
                            netRead.limit(read);
                            netRead.position(0);
                        }
                        appRead.clear();
                        SSLEngineResult result = engine.unwrap(netRead, appRead);
                        if (result.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                            appRead = ByteBuffer.allocate(appRead.capacity() * 2);
                            continue;
                        }
                        if (result.getStatus() == SSLEngineResult.Status.CLOSED) {
                            throw new EOFException("TLS session closed during handshake");
                        }
                        if (engine.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.FINISHED) {
                            return;
                        }
                    }
                    case NEED_WRAP -> {
                        netWrite.clear();
                        appWrite.clear();
                        SSLEngineResult result = engine.wrap(appWrite, netWrite);
                        if (result.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                            netWrite = ByteBuffer.allocate(netWrite.capacity() * 2);
                            continue;
                        }
                        netWrite.flip();
                        byte[] outgoing = new byte[netWrite.remaining()];
                        netWrite.get(outgoing);
                        socket.getOutputStream().write(outgoing);
                        socket.getOutputStream().flush();
                    }
                    case FINISHED -> {
                        return;
                    }
                    case NOT_HANDSHAKING -> {
                        return;
                    }
                    default -> throw new IOException("unexpected handshake status " + status);
                }
            }
        }

        @Override
        public @NotNull InputStream inputStream() throws IOException {
            if (decryptedInput != null) return decryptedInput;
            decryptedInput = new SSLEngineInputStream(socket, engine, netRead, appRead);
            return decryptedInput;
        }

        @Override
        public @NotNull OutputStream outputStream() throws IOException {
            return new SSLEngineOutputStream(socket, engine, appWrite);
        }
    }

    /** Decrypts bytes from the client into an InputStream, unwrapping TLS records on demand. */
    private static final class SSLEngineInputStream extends InputStream {
        private final Socket socket;
        private final SSLEngine engine;
        private final ByteBuffer netRead;
        private ByteBuffer appBuffer;
        private byte[] pending = new byte[0];
        private int pendingIndex;
        private boolean closed;

        SSLEngineInputStream(@NotNull Socket socket, @NotNull SSLEngine engine,
                              @NotNull ByteBuffer sharedNetRead, @NotNull ByteBuffer sharedAppBuffer) {
            this.socket = socket;
            this.engine = engine;
            this.netRead = sharedNetRead;
            this.appBuffer = sharedAppBuffer;
        }

        @Override
        public int read() throws IOException {
            if (closed) return -1;
            if (pendingIndex >= pending.length) {
                if (!fill()) return -1;
            }
            return pending[pendingIndex++] & 0xFF;
        }

        @Override
        public int read(byte @NotNull [] target, int offset, int length) throws IOException {
            if (closed) return -1;
            if (pendingIndex >= pending.length) {
                if (!fill()) return -1;
            }
            int count = Math.min(length, pending.length - pendingIndex);
            System.arraycopy(pending, pendingIndex, target, offset, count);
            pendingIndex += count;
            return count;
        }

        @Override
        public void close() throws IOException {
            closed = true;
        }

        private boolean fill() throws IOException {
            while (true) {
                // Unwrap whatever ciphertext is buffered; only read more on underflow.
                if (netRead.position() < netRead.limit()) {
                    appBuffer.clear();
                    SSLEngineResult result = engine.unwrap(netRead, appBuffer);
                    if (result.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                        appBuffer = ByteBuffer.allocate(appBuffer.capacity() * 2);
                        continue;
                    }
                    if (result.getStatus() == SSLEngineResult.Status.CLOSED) {
                        return false;
                    }
                    appBuffer.flip();
                    if (appBuffer.hasRemaining()) {
                        pending = new byte[appBuffer.remaining()];
                        appBuffer.get(pending);
                        pendingIndex = 0;
                        return true;
                    }
                    // Underflow: fall through to read more ciphertext.
                }
                netRead.compact();
                int read = socket.getInputStream().read(netRead.array(), netRead.position(), netRead.remaining());
                if (read < 0) {
                    engine.closeInbound();
                    return false;
                }
                netRead.position(netRead.position() + read);
                netRead.flip();
            }
        }
    }

    /** Encrypts written bytes to the client as TLS records. */
    private static final class SSLEngineOutputStream extends OutputStream {
        private final Socket socket;
        private final SSLEngine engine;
        private final ByteBuffer netWrite;
        private boolean closed;

        SSLEngineOutputStream(@NotNull Socket socket, @NotNull SSLEngine engine,
                              @NotNull ByteBuffer sharedAppBuffer) {
            this.socket = socket;
            this.engine = engine;
            this.netWrite = ByteBuffer.allocate(engine.getSession().getPacketBufferSize());
        }

        @Override
        public void write(int value) throws IOException {
            write(new byte[]{(byte) value});
        }

        @Override
        public void write(byte @NotNull [] data, int offset, int length) throws IOException {
            if (closed) throw new IOException("stream closed");
            ByteBuffer source = ByteBuffer.wrap(data, offset, length);
            while (source.hasRemaining()) {
                netWrite.clear();
                SSLEngineResult result = engine.wrap(source, netWrite);
                netWrite.flip();
                socket.getOutputStream().write(netWrite.array(), 0, netWrite.remaining());
            }
            socket.getOutputStream().flush();
        }

        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            engine.closeOutbound();
            netWrite.clear();
            SSLEngineResult result = engine.wrap(ByteBuffer.allocate(0), netWrite);
            netWrite.flip();
            socket.getOutputStream().write(netWrite.array(), 0, netWrite.remaining());
            socket.getOutputStream().flush();
        }
    }
}

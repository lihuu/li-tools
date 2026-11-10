package io.github.lihuu.mitmkit.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class RewriteRoundTripTest {

    @Test
    void rewritesRequestPathAndHeader(@TempDir Path tempDir) throws Exception {
        CertAuthority authority = new CertAuthority(tempDir.resolve("ca"));
        authority.ensureCa();

        String[] captured = new String[2];
        Upstream upstream = startUpstream(authority, captured);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        FlowRecorder recorder = new FlowRecorder(new PrintWriter(buffer, true, StandardCharsets.UTF_8), true, false);

        List<RewriteRule> rules = RewriteRule.parse(List.of(
                "url:/old->/new;reqHeader:X-Debug:1"
        ));

        ProxyServer proxy = new ProxyServer("127.0.0.1", 0, authority, recorder, true,
                false, false, false, rules);
        proxy.start();
        int proxyPort = proxy.port();
        try {
            String body = getThroughProxy("https://127.0.0.1:" + upstream.port + "/old", proxyPort, authority);
            assertEquals("hello", body);
            assertEquals("/new", captured[0], "upstream should receive the rewritten path");
            assertEquals("1", captured[1], "upstream should receive the injected header");
        } finally {
            proxy.stop();
            upstream.stop();
            authority.close();
        }
    }

    @Test
    void rewritesResponseBody(@TempDir Path tempDir) throws Exception {
        CertAuthority authority = new CertAuthority(tempDir.resolve("ca"));
        authority.ensureCa();

        String[] captured = new String[2];
        Upstream upstream = startUpstream(authority, captured);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        FlowRecorder recorder = new FlowRecorder(new PrintWriter(buffer, true, StandardCharsets.UTF_8), true, false);

        List<RewriteRule> rules = RewriteRule.parse(List.of(
                "replaceBody:hello->goodbye"
        ));

        ProxyServer proxy = new ProxyServer("127.0.0.1", 0, authority, recorder, true,
                false, false, false, rules);
        proxy.start();
        int proxyPort = proxy.port();
        try {
            String body = getThroughProxy("https://127.0.0.1:" + upstream.port + "/hello", proxyPort, authority);
            assertEquals("goodbye", body, "response body should be rewritten");
        } finally {
            proxy.stop();
            upstream.stop();
            authority.close();
        }
    }

    private static Upstream startUpstream(CertAuthority authority, String[] captured) throws Exception {
        CertAuthority.Leaf leaf = authority.issue("127.0.0.1");
        char[] password = "changeit".toCharArray();
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, password);
        keyStore.setKeyEntry("upstream", leaf.key(), password,
                new java.security.cert.Certificate[]{leaf.certificate(), authority.caCertificate()});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, password);
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), null, new SecureRandom());

        SSLServerSocket serverSocket = (SSLServerSocket) sslContext.getServerSocketFactory().createServerSocket();
        serverSocket.bind(new InetSocketAddress("127.0.0.1", 0));
        int port = serverSocket.getLocalPort();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            while (!serverSocket.isClosed()) {
                try (SSLSocket socket = (SSLSocket) serverSocket.accept()) {
                    InputStream input = socket.getInputStream();
                    StringBuilder headers = new StringBuilder();
                    int current;
                    while ((current = input.read()) != -1) {
                        headers.append((char) current);
                        if (headers.length() >= 4
                                && headers.substring(headers.length() - 4).equals("\r\n\r\n")) break;
                    }
                    String request = headers.toString();
                    String path = request.lines().findFirst().orElse("").split(" ")[1];
                    String debug = request.lines()
                            .filter(line -> line.toLowerCase().startsWith("x-debug:"))
                            .map(line -> line.substring(line.indexOf(':') + 1).trim())
                            .findFirst().orElse(null);
                    captured[0] = path;
                    captured[1] = debug;
                    byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
                    String response = "HTTP/1.1 200 OK\r\nContent-Length: " + body.length
                            + "\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\n";
                    socket.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().write(body);
                    socket.getOutputStream().flush();
                } catch (Exception ignored) {
                    return;
                }
            }
        });
        return new Upstream(serverSocket, port, executor);
    }

    private static String getThroughProxy(String target, int proxyPort, CertAuthority authority) throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, null);
        trustStore.setCertificateEntry("mitm", authority.caCertificate());
        tmf.init(trustStore);
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, tmf.getTrustManagers(), new SecureRandom());
        javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());
        URL url = URI.create(target).toURL();
        URLConnection connection = url.openConnection(new Proxy(Proxy.Type.HTTP,
                new InetSocketAddress("127.0.0.1", proxyPort)));
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        try (InputStream input = connection.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private record Upstream(SSLServerSocket serverSocket, int port, ExecutorService executor) {
        void stop() throws Exception {
            executor.shutdownNow();
            serverSocket.close();
        }
    }
}

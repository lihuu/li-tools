package io.github.lihuu.certkit;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;

public final class CertParser {

    private CertParser() {}

    public static CertInfo parse(Path certPath) throws IOException, CertificateException {
        byte[] bytes = Files.readAllBytes(certPath);
        return parse(bytes);
    }

    public static CertInfo parse(byte[] certPemBytes) throws CertificateException {
        try (InputStream in = new ByteArrayInputStream(certPemBytes)) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate) cf.generateCertificate(in);
            if (cert == null) {
                throw new CertificateException("No certificate found in provided bytes");
            }
            return extractInfo(cert);
        } catch (IOException e) {
            throw new CertificateException("Failed to read certificate stream", e);
        }
    }

    public static CertInfo parseRemote(String host, int port, int timeoutMillis) throws IOException, CertificateException {
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        try (SSLSocket socket = (SSLSocket) factory.createSocket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            socket.startHandshake();
            Certificate[] certs = socket.getSession().getPeerCertificates();
            if (certs == null || certs.length == 0 || !(certs[0] instanceof X509Certificate x509)) {
                throw new CertificateException("No X.509 peer certificates returned by " + host + ":" + port);
            }
            return extractInfo(x509);
        }
    }

    public static CertInfo extractInfo(X509Certificate cert) throws CertificateEncodingException {
        String dn = cert.getSubjectX500Principal().getName();
        String cn = extractCn(dn);

        List<String> sans = new ArrayList<>();
        try {
            Collection<List<?>> altNames = cert.getSubjectAlternativeNames();
            if (altNames != null) {
                for (List<?> item : altNames) {
                    if (item.size() >= 2) {
                        int type = ((Number) item.get(0)).intValue();
                        String value = String.valueOf(item.get(1));
                        if (type == 2) {
                            sans.add("DNS:" + value);
                        } else if (type == 7) {
                            sans.add("IP:" + value);
                        } else {
                            sans.add("OTHER(" + type + "):" + value);
                        }
                    }
                }
            }
        } catch (CertificateException ignored) {
            // No SANs or parsing error
        }

        String issuer = cert.getIssuerX500Principal().getName();
        String sha256 = calculateSha256(cert.getEncoded());
        String sigAlg = cert.getSigAlgName();
        String serial = cert.getSerialNumber().toString(16).toUpperCase();

        return new CertInfo(
            cn,
            List.copyOf(sans),
            issuer,
            cert.getNotBefore().toInstant(),
            cert.getNotAfter().toInstant(),
            sha256,
            sigAlg,
            serial
        );
    }

    private static String extractCn(String dn) {
        for (String part : dn.split(",")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("CN=")) {
                return trimmed.substring(3);
            }
        }
        return dn;
    }

    private static String calculateSha256(byte[] encoded) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(encoded);
            return HexFormat.ofDelimiter(":").withUpperCase().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}

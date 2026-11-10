package io.github.lihuu.mitmkit.core;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.jetbrains.annotations.NotNull;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * Generates the local root CA and signs per-host leaf certificates for MITM interception.
 *
 * <p>All cryptographic work is in-process via BouncyCastle; no openssl subprocess is involved.
 */
public final class CertAuthority implements AutoCloseable {
    private static final String CA_CN = "mitmkit local CA";
    private static final long CA_DAYS = 3650;
    private static final long LEAF_DAYS = 365;

    private final Path caDir;
    private final SecureRandom random = new SecureRandom();
    private PrivateKey caKey;
    private X509Certificate caCert;
    private boolean closed;

    public CertAuthority(@NotNull Path caDir) {
        this.caDir = caDir;
    }

    /** Returns the CA certificate, generating the CA on first use. */
    public @NotNull X509Certificate ensureCa() throws Exception {
        if (caCert != null) return caCert;
        Path certPath = caDir.resolve("ca.crt");
        Path keyPath = caDir.resolve("ca.key");
        if (Files.exists(certPath) && Files.exists(keyPath)) {
            loadCa(certPath, keyPath);
            return caCert;
        }
        Files.createDirectories(caDir);
        generateCa(certPath, keyPath);
        loadCa(certPath, keyPath);
        return caCert;
    }

    public @NotNull X509Certificate caCertificate() throws Exception {
        ensureCa();
        return caCert;
    }

    /** Signs a leaf certificate for the given host (DNS name or IP literal), carrying a matching SAN. */
    public @NotNull Leaf issue(@NotNull String host) throws Exception {
        ensureCa();
        if (closed) throw new IllegalStateException("CertAuthority is closed");

        KeyPair leafPair = generateKeyPair();
        X509Certificate certificate = signLeaf(host, leafPair);
        return new Leaf(certificate, leafPair.getPrivate());
    }

    /** Signs a leaf certificate for the given host (DNS name or IP literal), carrying a matching SAN. */
    public @NotNull X509Certificate leafCertificate(@NotNull String host) throws Exception {
        return issue(host).certificate();
    }

    public record Leaf(@NotNull X509Certificate certificate, @NotNull PrivateKey key) {}

    private @NotNull X509Certificate signLeaf(@NotNull String host, @NotNull KeyPair leafPair) throws Exception {
        X500Name issuer = new X500Name(caCert.getSubjectX500Principal().getName());
        X500Name subject = new X500Name("CN=" + host);
        BigInteger serial = new BigInteger(128, random);
        Instant now = Instant.now();
        Date notBefore = Date.from(now.minus(1, ChronoUnit.DAYS));
        Date notAfter = Date.from(now.plus(LEAF_DAYS, ChronoUnit.DAYS));

        GeneralName name = isIpLiteral(host)
                ? new GeneralName(GeneralName.iPAddress, host)
                : new GeneralName(GeneralName.dNSName, host);
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                issuer, serial, notBefore, notAfter, subject, leafPair.getPublic());
        builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(name));
        builder.addExtension(Extension.basicConstraints, false, new BasicConstraints(false));
        builder.addExtension(Extension.keyUsage, true,
                new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));

        ContentSigner signer = new JcaContentSignerBuilder("SHA256withECDSA")
                .build(caKey);
        X509CertificateHolder holder = builder.build(signer);
        return new JcaX509CertificateConverter().getCertificate(holder);
    }

    @Override
    public void close() {
        closed = true;
        caKey = null;
        caCert = null;
    }

    private void generateCa(@NotNull Path certPath, @NotNull Path keyPath) throws Exception {
        KeyPair caPair = generateKeyPair();
        X500Name name = new X500Name("CN=" + CA_CN);
        BigInteger serial = new BigInteger(128, random);
        Instant now = Instant.now();
        Date notBefore = Date.from(now.minus(1, ChronoUnit.DAYS));
        Date notAfter = Date.from(now.plus(CA_DAYS, ChronoUnit.DAYS));

        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                name, serial, notBefore, notAfter, name, caPair.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        builder.addExtension(Extension.keyUsage, true,
                new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign | KeyUsage.digitalSignature));

        ContentSigner signer = new JcaContentSignerBuilder("SHA256withECDSA")
                .build(caPair.getPrivate());
        X509CertificateHolder holder = builder.build(signer);
        X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(holder);

        Files.writeString(certPath, pemCertificate(certificate));
        writeKeyPem(keyPath, caPair.getPrivate());
    }

    private void loadCa(@NotNull Path certPath, @NotNull Path keyPath) throws Exception {
        String certPem = Files.readString(certPath);
        String keyPem = Files.readString(keyPath);
        caCert = parseCertificate(certPem);
        caKey = parsePrivateKey(keyPem);
    }

    private @NotNull KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(256, random);
        return generator.generateKeyPair();
    }

    private static @NotNull X509Certificate parseCertificate(@NotNull String pem) throws Exception {
        String base64 = pem.replaceAll("-----BEGIN CERTIFICATE-----", "")
                .replaceAll("-----END CERTIFICATE-----", "")
                .replaceAll("\\s", "");
        byte[] der = java.util.Base64.getDecoder().decode(base64);
        return (X509Certificate) java.security.cert.CertificateFactory
                .getInstance("X.509")
                .generateCertificate(new java.io.ByteArrayInputStream(der));
    }

    private static @NotNull PrivateKey parsePrivateKey(@NotNull String pem) throws Exception {
        String base64 = pem.replaceAll("-----BEGIN (EC |PKCS8 )?PRIVATE KEY-----", "")
                .replaceAll("-----END (EC |PKCS8 )?PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = java.util.Base64.getDecoder().decode(base64);
        java.security.KeyFactory factory = java.security.KeyFactory.getInstance("EC");
        // Try PKCS#8 first; fall back to EC SEC1 by wrapping is not possible, so require PKCS#8.
        return factory.generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(der));
    }

    private static @NotNull String pemCertificate(@NotNull X509Certificate certificate) throws Exception {
        String base64 = java.util.Base64.getEncoder().encodeToString(certificate.getEncoded());
        return "-----BEGIN CERTIFICATE-----\n" + wrap(base64) + "-----END CERTIFICATE-----\n";
    }

    private static void writeKeyPem(@NotNull Path keyPath, @NotNull PrivateKey key) throws Exception {
        String base64 = java.util.Base64.getEncoder().encodeToString(key.getEncoded());
        String pem = "-----BEGIN PRIVATE KEY-----\n" + wrap(base64) + "-----END PRIVATE KEY-----\n";
        Files.writeString(keyPath, pem);
        if (Files.getFileAttributeView(keyPath, java.nio.file.attribute.PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(keyPath, PosixFilePermissions.fromString("rw-------"));
        }
    }

    private static @NotNull String wrap(@NotNull String base64) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < base64.length(); index += 64) {
            builder.append(base64, index, Math.min(index + 64, base64.length())).append('\n');
        }
        return builder.toString();
    }

    private static boolean isIpLiteral(@NotNull String host) {
        return host.matches("\\d{1,3}(\\.\\d{1,3}){3}") || host.contains(":");
    }
}

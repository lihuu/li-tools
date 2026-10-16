package io.github.lihuu.certkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class Openssl {

    private final String opensslPath;

    public Openssl() {
        this("openssl");
    }

    public Openssl(String opensslPath) {
        this.opensslPath = opensslPath;
    }

    public void checkAvailable() {
        try {
            Process process = new ProcessBuilder(opensslPath, "version")
                .redirectErrorStream(true)
                .start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException("openssl exited with non-zero code: " + exitCode);
            }
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("OpenSSL is not installed or not found in PATH (" + opensslPath + "). Please install openssl first.", e);
        }
    }

    public CertInfo generateSelfSigned(
        List<String> domains,
        List<String> ips,
        int days,
        String algo,
        Path certOut,
        Path keyOut
    ) throws IOException, CertificateException, InterruptedException {
        checkAvailable();

        List<String> effectiveDomains = (domains == null || domains.isEmpty()) ? List.of("localhost") : domains;
        List<String> effectiveIps = (ips == null || ips.isEmpty()) ? List.of("127.0.0.1") : ips;
        String primaryCn = effectiveDomains.getFirst();

        // 1. Build temporary openssl config with SAN
        Path tempCnf = Files.createTempFile("certkit-openssl-", ".cnf");
        try {
            StringBuilder cnfContent = new StringBuilder();
            cnfContent.append("""
                [req]
                distinguished_name = req_distinguished_name
                x509_extensions = v3_req
                prompt = no

                [req_distinguished_name]
                CN = %s

                [v3_req]
                keyUsage = digitalSignature, keyEncipherment
                extendedKeyUsage = serverAuth, clientAuth
                subjectAltName = @alt_names

                [alt_names]
                """.formatted(primaryCn));

            int dnsIndex = 1;
            for (String domain : effectiveDomains) {
                cnfContent.append("DNS.").append(dnsIndex++).append(" = ").append(domain).append("\n");
            }

            int ipIndex = 1;
            for (String ip : effectiveIps) {
                cnfContent.append("IP.").append(ipIndex++).append(" = ").append(ip).append("\n");
            }

            Files.writeString(tempCnf, cnfContent.toString(), StandardCharsets.UTF_8);

            // Ensure parent directories exist
            if (certOut.getParent() != null) {
                Files.createDirectories(certOut.getParent());
            }
            if (keyOut.getParent() != null) {
                Files.createDirectories(keyOut.getParent());
            }

            // 2. Build openssl command
            List<String> cmd = new ArrayList<>();
            cmd.add(opensslPath);
            cmd.add("req");
            cmd.add("-x509");
            if ("rsa4096".equalsIgnoreCase(algo)) {
                cmd.add("-newkey");
                cmd.add("rsa:4096");
            } else {
                // Default: modern ECDSA (P-256)
                cmd.add("-newkey");
                cmd.add("ec");
                cmd.add("-pkeyopt");
                cmd.add("ec_paramgen_curve:prime256v1");
            }
            cmd.add("-nodes");
            cmd.add("-keyout");
            cmd.add(keyOut.toAbsolutePath().toString());
            cmd.add("-out");
            cmd.add(certOut.toAbsolutePath().toString());
            cmd.add("-days");
            cmd.add(String.valueOf(days));
            cmd.add("-config");
            cmd.add(tempCnf.toAbsolutePath().toString());

            Process process = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start();

            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException("OpenSSL command failed (exit code " + exitCode + "): " + output);
            }

            // 3. Set secure permissions on private key (0600)
            try {
                Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rw-------");
                Files.setPosixFilePermissions(keyOut, perms);
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX system (e.g. Windows)
            }

            // 4. Loopback verification: Parse newly generated certificate and verify SANs
            CertInfo info = CertParser.parse(certOut);
            for (String domain : effectiveDomains) {
                if (!info.sans().contains("DNS:" + domain)) {
                    throw new IllegalStateException("Verification failed: SAN does not contain expected domain DNS:" + domain);
                }
            }
            for (String ip : effectiveIps) {
                if (!info.sans().contains("IP:" + ip)) {
                    throw new IllegalStateException("Verification failed: SAN does not contain expected IP:" + ip);
                }
            }

            return info;
        } finally {
            Files.deleteIfExists(tempCnf);
        }
    }
}

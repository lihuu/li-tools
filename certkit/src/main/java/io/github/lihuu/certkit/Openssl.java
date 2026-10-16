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
            throw new IllegalStateException(missingOpensslMessage(), e);
        }
    }

    public String getVersion() {
        try {
            Process process = new ProcessBuilder(opensslPath, "version")
                .redirectErrorStream(true)
                .start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int code = process.waitFor();
            return (code == 0) ? out : "Unknown (exit code " + code + ")";
        } catch (Exception e) {
            return "Unavailable (" + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()) + ")";
        }
    }

    public String resolveBinaryPath() {
        try {
            Process process = new ProcessBuilder("which", opensslPath)
                .redirectErrorStream(true)
                .start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int code = process.waitFor();
            return (code == 0 && !out.isEmpty()) ? out : opensslPath;
        } catch (Exception e) {
            return opensslPath;
        }
    }

    public static String missingOpensslMessage() {
        return """
            ✖ OpenSSL is not installed or not found in PATH.

            certkit relies on OpenSSL for cryptographic operations.
            Please install it with one command:
              macOS:         brew install openssl
              Ubuntu/Debian: sudo apt update && sudo apt install -y openssl
              RHEL/Fedora:   sudo dnf install -y openssl
              Alpine Linux:  apk add openssl
            """;
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

            ensureParentDir(certOut);
            ensureParentDir(keyOut);

            List<String> cmd = new ArrayList<>();
            cmd.add(opensslPath);
            cmd.add("req");
            cmd.add("-x509");
            applyKeyAlgo(cmd, algo);
            cmd.add("-nodes");
            cmd.add("-keyout");
            cmd.add(keyOut.toAbsolutePath().toString());
            cmd.add("-out");
            cmd.add(certOut.toAbsolutePath().toString());
            cmd.add("-days");
            cmd.add(String.valueOf(days));
            cmd.add("-config");
            cmd.add(tempCnf.toAbsolutePath().toString());

            runCommand(cmd);
            setKeyPermissions(keyOut);

            // Loopback verification: Parse newly generated certificate and verify SANs
            CertInfo info = CertParser.parse(certOut);
            verifySans(info, effectiveDomains, effectiveIps);

            return info;
        } finally {
            Files.deleteIfExists(tempCnf);
        }
    }

    public CertInfo generateCa(
        String commonName,
        int days,
        String algo,
        Path caCertOut,
        Path caKeyOut
    ) throws IOException, CertificateException, InterruptedException {
        checkAvailable();

        Path tempCnf = Files.createTempFile("certkit-ca-", ".cnf");
        try {
            String cnfContent = """
                [req]
                distinguished_name = req_dn
                x509_extensions = v3_ca
                prompt = no

                [req_dn]
                CN = %s

                [v3_ca]
                basicConstraints = critical, CA:TRUE
                keyUsage = critical, digitalSignature, cRLSign, keyCertSign
                subjectKeyIdentifier = hash
                authorityKeyIdentifier = keyid:always,issuer
                """.formatted(commonName);

            Files.writeString(tempCnf, cnfContent, StandardCharsets.UTF_8);

            ensureParentDir(caCertOut);
            ensureParentDir(caKeyOut);

            List<String> cmd = new ArrayList<>();
            cmd.add(opensslPath);
            cmd.add("req");
            cmd.add("-x509");
            applyKeyAlgo(cmd, algo);
            cmd.add("-nodes");
            cmd.add("-keyout");
            cmd.add(caKeyOut.toAbsolutePath().toString());
            cmd.add("-out");
            cmd.add(caCertOut.toAbsolutePath().toString());
            cmd.add("-days");
            cmd.add(String.valueOf(days));
            cmd.add("-config");
            cmd.add(tempCnf.toAbsolutePath().toString());

            runCommand(cmd);
            setKeyPermissions(caKeyOut);

            CertInfo info = CertParser.parse(caCertOut);
            return info;
        } finally {
            Files.deleteIfExists(tempCnf);
        }
    }

    public CertInfo generateSignedByCa(
        List<String> domains,
        List<String> ips,
        int days,
        String algo,
        Path caCert,
        Path caKey,
        Path certOut,
        Path keyOut,
        Path fullchainOut
    ) throws IOException, CertificateException, InterruptedException {
        checkAvailable();

        List<String> effectiveDomains = (domains == null || domains.isEmpty()) ? List.of("localhost") : domains;
        List<String> effectiveIps = (ips == null || ips.isEmpty()) ? List.of("127.0.0.1") : ips;
        String primaryCn = effectiveDomains.getFirst();

        Path tempCnf = Files.createTempFile("certkit-csr-", ".cnf");
        Path tempCsr = Files.createTempFile("certkit-csr-", ".csr");
        try {
            StringBuilder cnfContent = new StringBuilder();
            cnfContent.append("""
                [req]
                distinguished_name = req_dn
                req_extensions = v3_req
                prompt = no

                [req_dn]
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

            ensureParentDir(certOut);
            ensureParentDir(keyOut);

            // Step 1: Generate CSR + Key
            List<String> csrCmd = new ArrayList<>();
            csrCmd.add(opensslPath);
            csrCmd.add("req");
            csrCmd.add("-new");
            applyKeyAlgo(csrCmd, algo);
            csrCmd.add("-nodes");
            csrCmd.add("-keyout");
            csrCmd.add(keyOut.toAbsolutePath().toString());
            csrCmd.add("-out");
            csrCmd.add(tempCsr.toAbsolutePath().toString());
            csrCmd.add("-config");
            csrCmd.add(tempCnf.toAbsolutePath().toString());
            runCommand(csrCmd);
            setKeyPermissions(keyOut);

            // Step 2: Sign CSR with CA
            List<String> signCmd = new ArrayList<>();
            signCmd.add(opensslPath);
            signCmd.add("x509");
            signCmd.add("-req");
            signCmd.add("-in");
            signCmd.add(tempCsr.toAbsolutePath().toString());
            signCmd.add("-CA");
            signCmd.add(caCert.toAbsolutePath().toString());
            signCmd.add("-CAkey");
            signCmd.add(caKey.toAbsolutePath().toString());
            signCmd.add("-CAcreateserial");
            signCmd.add("-out");
            signCmd.add(certOut.toAbsolutePath().toString());
            signCmd.add("-days");
            signCmd.add(String.valueOf(days));
            signCmd.add("-extfile");
            signCmd.add(tempCnf.toAbsolutePath().toString());
            signCmd.add("-extensions");
            signCmd.add("v3_req");
            runCommand(signCmd);

            // Step 3: Bundle fullchain (cert + caCert)
            if (fullchainOut != null) {
                ensureParentDir(fullchainOut);
                byte[] certBytes = Files.readAllBytes(certOut);
                byte[] caBytes = Files.readAllBytes(caCert);
                byte[] combined = new byte[certBytes.length + 1 + caBytes.length];
                System.arraycopy(certBytes, 0, combined, 0, certBytes.length);
                combined[certBytes.length] = '\n';
                System.arraycopy(caBytes, 0, combined, certBytes.length + 1, caBytes.length);
                Files.write(fullchainOut, combined);
            }

            // Step 4: Verify with CA
            List<String> verifyCmd = List.of(
                opensslPath, "verify", "-CAfile", caCert.toAbsolutePath().toString(), certOut.toAbsolutePath().toString()
            );
            runCommand(verifyCmd);

            CertInfo info = CertParser.parse(certOut);
            verifySans(info, effectiveDomains, effectiveIps);
            return info;
        } finally {
            Files.deleteIfExists(tempCnf);
            Files.deleteIfExists(tempCsr);
        }
    }

    public void convert(Path inFile, Path inKey, Path outFile, String passphrase) throws IOException, InterruptedException {
        checkAvailable();
        ensureParentDir(outFile);

        String inName = inFile.getFileName().toString().toLowerCase();
        String outName = outFile.getFileName().toString().toLowerCase();
        String pass = (passphrase == null || passphrase.isBlank()) ? "changeit" : passphrase;

        List<String> cmd = new ArrayList<>();
        cmd.add(opensslPath);

        if ((outName.endsWith(".p12") || outName.endsWith(".pfx"))) {
            // PEM -> P12
            if (inKey == null || !Files.exists(inKey)) {
                throw new IllegalArgumentException("Converting to PKCS#12 (.p12) requires private key file (--key)");
            }
            cmd.add("pkcs12");
            cmd.add("-export");
            cmd.add("-in");
            cmd.add(inFile.toAbsolutePath().toString());
            cmd.add("-inkey");
            cmd.add(inKey.toAbsolutePath().toString());
            cmd.add("-out");
            cmd.add(outFile.toAbsolutePath().toString());
            cmd.add("-passout");
            cmd.add("pass:" + pass);
        } else if ((inName.endsWith(".p12") || inName.endsWith(".pfx")) && outName.endsWith(".pem")) {
            // P12 -> PEM
            cmd.add("pkcs12");
            cmd.add("-in");
            cmd.add(inFile.toAbsolutePath().toString());
            cmd.add("-out");
            cmd.add(outFile.toAbsolutePath().toString());
            cmd.add("-nodes");
            cmd.add("-passin");
            cmd.add("pass:" + pass);
        } else if (outName.endsWith(".der")) {
            // PEM -> DER
            cmd.add("x509");
            cmd.add("-outform");
            cmd.add("der");
            cmd.add("-in");
            cmd.add(inFile.toAbsolutePath().toString());
            cmd.add("-out");
            cmd.add(outFile.toAbsolutePath().toString());
        } else if (inName.endsWith(".der") && outName.endsWith(".pem")) {
            // DER -> PEM
            cmd.add("x509");
            cmd.add("-inform");
            cmd.add("der");
            cmd.add("-in");
            cmd.add(inFile.toAbsolutePath().toString());
            cmd.add("-out");
            cmd.add(outFile.toAbsolutePath().toString());
        } else {
            throw new IllegalArgumentException("Unsupported conversion from " + inName + " to " + outName);
        }

        runCommand(cmd);
    }

    private void applyKeyAlgo(List<String> cmd, String algo) {
        if ("rsa4096".equalsIgnoreCase(algo)) {
            cmd.add("-newkey");
            cmd.add("rsa:4096");
        } else {
            cmd.add("-newkey");
            cmd.add("ec");
            cmd.add("-pkeyopt");
            cmd.add("ec_paramgen_curve:prime256v1");
        }
    }

    private void runCommand(List<String> cmd) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("OpenSSL command failed (" + exitCode + "): " + output.trim());
        }
    }

    private void setKeyPermissions(Path keyPath) {
        try {
            Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(keyPath, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX fallback or permission error
        }
    }

    private void ensureParentDir(Path file) throws IOException {
        if (file.getParent() != null && !Files.exists(file.getParent())) {
            Files.createDirectories(file.getParent());
        }
    }

    private void verifySans(CertInfo info, List<String> domains, List<String> ips) {
        for (String domain : domains) {
            if (!info.sans().contains("DNS:" + domain)) {
                throw new IllegalStateException("Verification failed: SAN missing DNS:" + domain);
            }
        }
        for (String ip : ips) {
            if (!info.sans().contains("IP:" + ip)) {
                throw new IllegalStateException("Verification failed: SAN missing IP:" + ip);
            }
        }
    }
}

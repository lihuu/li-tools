package io.github.lihuu.certkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

public final class TrustStore {

    public enum Platform {
        MACOS,
        LINUX_DEBIAN,
        LINUX_RHEL,
        LINUX_ARCH,
        UNSUPPORTED
    }

    private final Platform platform;

    public TrustStore() {
        this(detectPlatform());
    }

    public TrustStore(Platform platform) {
        this.platform = platform;
    }

    public Platform getPlatform() {
        return platform;
    }

    public static Platform detectPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac") || os.contains("darwin")) {
            return Platform.MACOS;
        }
        if (os.contains("linux")) {
            Path osRelease = Path.of("/etc/os-release");
            if (Files.exists(osRelease)) {
                try {
                    String content = Files.readString(osRelease).toLowerCase();
                    if (content.contains("debian") || content.contains("ubuntu")) {
                        return Platform.LINUX_DEBIAN;
                    }
                    if (content.contains("rhel") || content.contains("fedora") || content.contains("centos")) {
                        return Platform.LINUX_RHEL;
                    }
                    if (content.contains("arch")) {
                        return Platform.LINUX_ARCH;
                    }
                } catch (IOException ignored) {}
            }
            return Platform.LINUX_DEBIAN; // default Linux fallback
        }
        return Platform.UNSUPPORTED;
    }

    public TrustResult install(Path caCert, boolean system) throws Exception {
        if (!Files.exists(caCert)) {
            throw new IllegalArgumentException("CA certificate file does not exist: " + caCert);
        }
        CertInfo info = CertParser.parse(caCert);

        return switch (platform) {
            case MACOS -> installMacos(caCert, system, info);
            case LINUX_DEBIAN, LINUX_RHEL, LINUX_ARCH -> installLinux(caCert, info);
            default -> new TrustResult(false, "Unsupported platform for automated trust installation. Please import manually.", null);
        };
    }

    public TrustResult check(Path caCert) throws Exception {
        if (!Files.exists(caCert)) {
            throw new IllegalArgumentException("CA certificate file does not exist: " + caCert);
        }
        CertInfo info = CertParser.parse(caCert);

        if (platform == Platform.MACOS) {
            List<String> cmd = List.of("security", "find-certificate", "-c", info.subjectCn());
            CommandResult res = runSilently(cmd);
            boolean trusted = res.exitCode == 0 && res.output.contains(info.subjectCn());
            return new TrustResult(trusted, trusted ? "CA certificate is present in macOS Keychain." : "CA certificate not found in macOS Keychain.", null);
        }

        return new TrustResult(false, "Platform check for Linux trust store is best verified via curl/openssl.", null);
    }

    public TrustResult uninstall(Path caCert, boolean system) throws Exception {
        if (!Files.exists(caCert)) {
            throw new IllegalArgumentException("CA certificate file does not exist: " + caCert);
        }
        CertInfo info = CertParser.parse(caCert);

        if (platform == Platform.MACOS) {
            List<String> cmd = new ArrayList<>();
            cmd.add("security");
            cmd.add("remove-trusted-cert");
            cmd.add(caCert.toAbsolutePath().toString());
            CommandResult res = runSilently(cmd);
            if (res.exitCode == 0) {
                return new TrustResult(true, "Successfully removed trust settings for " + info.subjectCn(), null);
            } else {
                return new TrustResult(false, "Failed to remove trust: " + res.output.trim(), "security remove-trusted-cert " + caCert.toAbsolutePath());
            }
        }

        return new TrustResult(false, "Uninstall for this platform requires manual cleanup.", null);
    }

    private TrustResult installMacos(Path caCert, boolean system, CertInfo info) {
        String keychain;
        if (system) {
            keychain = "/Library/Keychains/System.keychain";
        } else {
            String userHome = System.getProperty("user.home");
            keychain = userHome + "/Library/Keychains/login.keychain-db";
        }

        List<String> cmd = List.of(
            "security", "add-trusted-cert",
            "-d",
            "-r", "trustRoot",
            "-k", keychain,
            caCert.toAbsolutePath().toString()
        );

        CommandResult res = runSilently(cmd);
        if (res.exitCode == 0) {
            return new TrustResult(true, "Successfully installed and trusted CA in Keychain: " + keychain, null);
        } else {
            String suggestCmd = (system ? "sudo " : "") + String.join(" ", cmd);
            return new TrustResult(false, "Keychain write denied. Admin privileges required.", suggestCmd);
        }
    }

    private TrustResult installLinux(Path caCert, CertInfo info) {
        Path targetDir;
        String updateCmd;

        switch (platform) {
            case LINUX_RHEL -> {
                targetDir = Path.of("/etc/pki/ca-trust/source/anchors");
                updateCmd = "update-ca-trust extract";
            }
            case LINUX_ARCH -> {
                targetDir = Path.of("/etc/ca-certificates/trust-source/anchors");
                updateCmd = "trust extract-compat";
            }
            default -> { // Debian / Ubuntu
                targetDir = Path.of("/usr/local/share/ca-certificates");
                updateCmd = "update-ca-certificates";
            }
        }

        String fileName = sanitizeFileName(info.subjectCn()) + ".crt";
        Path targetFile = targetDir.resolve(fileName);

        try {
            if (Files.isWritable(targetDir)) {
                Files.copy(caCert, targetFile, StandardCopyOption.REPLACE_EXISTING);
                CommandResult res = runSilently(List.of("/bin/sh", "-c", updateCmd));
                if (res.exitCode == 0) {
                    return new TrustResult(true, "CA installed to " + targetFile + " and trust updated.", null);
                }
            }
        } catch (IOException ignored) {}

        String suggestCmd = String.format("sudo cp '%s' '%s' && sudo %s", caCert.toAbsolutePath(), targetFile, updateCmd);
        return new TrustResult(false, "Root privileges required to update system trust store.", suggestCmd);
    }

    private String sanitizeFileName(String name) {
        return name.replaceAll("[^a-zA-Z0-9.-]", "_");
    }

    private CommandResult runSilently(List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = p.waitFor();
            return new CommandResult(code, out);
        } catch (Exception e) {
            return new CommandResult(-1, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    public record TrustResult(boolean success, String message, String suggestedCommand) {}
    private record CommandResult(int exitCode, String output) {}
}

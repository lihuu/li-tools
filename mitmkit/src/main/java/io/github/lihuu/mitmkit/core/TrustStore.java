package io.github.lihuu.mitmkit.core;

import io.github.lihuu.clicommon.system.Platform;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Installs and removes the local CA in the operating system trust store.
 *
 * <p>Implementation mirrors certkit's three-platform logic; mitmkit keeps its own copy so
 * the tools stay independent. Every failure returns a result carrying a copy-pasteable
 * remediation command instead of throwing.
 */
public final class TrustStore {
    private final Platform.OS platform;

    public TrustStore() {
        this(Platform.current());
    }

    public TrustStore(@NotNull Platform.OS platform) {
        this.platform = platform;
    }

    public @NotNull TrustResult install(@NotNull Path caCert) {
        if (!Files.exists(caCert)) {
            return new TrustResult(false, "CA certificate file does not exist: " + caCert, null);
        }
        return switch (platform) {
            case MACOS -> installMacos(caCert);
            case WINDOWS -> installWindows(caCert);
            case LINUX -> installLinux(caCert);
            default -> new TrustResult(false, "Unsupported platform for automated trust installation.", null);
        };
    }

    public @NotNull TrustResult check(@NotNull Path caCert) {
        if (!Files.exists(caCert)) {
            return new TrustResult(false, "CA certificate file does not exist: " + caCert, null);
        }
        String subject = caSubject(caCert);
        if (platform == Platform.OS.MACOS) {
            CommandResult res = runSilently(List.of("security", "find-certificate", "-c", subject));
            boolean trusted = res.exitCode == 0 && res.output.contains(subject);
            return new TrustResult(trusted,
                    trusted ? "CA is present in the macOS Keychain." : "CA is not present in the macOS Keychain.",
                    null);
        }
        if (platform == Platform.OS.WINDOWS) {
            CommandResult user = runSilently(List.of("certutil.exe", "-user", "-store", "Root", subject));
            CommandResult system = runSilently(List.of("certutil.exe", "-store", "Root", subject));
            boolean trusted = (user.exitCode == 0 && user.output.contains(subject))
                    || (system.exitCode == 0 && system.output.contains(subject));
            return new TrustResult(trusted,
                    trusted ? "CA is present in the Windows Root store." : "CA is not present in the Windows Root store.",
                    null);
        }
        return new TrustResult(false, "Trust check on this platform is best verified with curl or openssl.", null);
    }

    public @NotNull TrustResult uninstall(@NotNull Path caCert) {
        if (!Files.exists(caCert)) {
            return new TrustResult(false, "CA certificate file does not exist: " + caCert, null);
        }
        String subject = caSubject(caCert);
        if (platform == Platform.OS.MACOS) {
            CommandResult res = runSilently(List.of("security", "remove-trusted-cert",
                    caCert.toAbsolutePath().toString()));
            return res.exitCode == 0
                    ? new TrustResult(true, "Removed trust for " + subject, null)
                    : new TrustResult(false, "Failed to remove trust: " + res.output.trim(),
                    "security remove-trusted-cert " + caCert.toAbsolutePath());
        }
        if (platform == Platform.OS.WINDOWS) {
            CommandResult user = runSilently(List.of("certutil.exe", "-f", "-delstore", "-user", "Root", subject));
            CommandResult system = runSilently(List.of("certutil.exe", "-f", "-delstore", "Root", subject));
            return (user.exitCode == 0 || system.exitCode == 0)
                    ? new TrustResult(true, "Removed CA from the Windows Root store: " + subject, null)
                    : new TrustResult(false, "Failed to remove CA from the Windows store: " + user.output.trim(),
                    "certutil.exe -f -delstore Root " + subject);
        }
        return new TrustResult(false, "Uninstall on this platform requires manual cleanup.", null);
    }

    private @NotNull TrustResult installMacos(@NotNull Path caCert) {
        String userHome = System.getProperty("user.home");
        String keychain = userHome + "/Library/Keychains/login.keychain-db";
        List<String> cmd = List.of("security", "add-trusted-cert", "-d", "-r", "trustRoot",
                "-k", keychain, caCert.toAbsolutePath().toString());
        CommandResult res = runSilently(cmd);
        if (res.exitCode == 0) {
            return new TrustResult(true, "Installed CA in the login keychain.", null);
        }
        return new TrustResult(false, "Keychain write denied. Admin privileges required.",
                "sudo " + String.join(" ", cmd));
    }

    private @NotNull TrustResult installWindows(@NotNull Path caCert) {
        CommandResult user = runSilently(List.of("certutil.exe", "-f", "-user", "-addstore", "Root",
                caCert.toAbsolutePath().toString()));
        if (user.exitCode == 0) {
            return new TrustResult(true, "Installed CA in the CurrentUser Root store.", null);
        }
        CommandResult system = runSilently(List.of("certutil.exe", "-f", "-addstore", "Root",
                caCert.toAbsolutePath().toString()));
        if (system.exitCode == 0) {
            return new TrustResult(true, "Installed CA in the LocalMachine Root store.", null);
        }
        return new TrustResult(false, "Failed to write to the Windows certificate store: " + user.output.trim(),
                "certutil.exe -f -user -addstore Root " + caCert.toAbsolutePath()
                        + " (or as Administrator: certutil.exe -f -addstore Root " + caCert.toAbsolutePath() + ")");
    }

    private @NotNull TrustResult installLinux(@NotNull Path caCert) {
        Path targetDir;
        String updateCmd;
        if (isRhel()) {
            targetDir = Path.of("/etc/pki/ca-trust/source/anchors");
            updateCmd = "update-ca-trust extract";
        } else if (isArch()) {
            targetDir = Path.of("/etc/ca-certificates/trust-source/anchors");
            updateCmd = "trust extract-compat";
        } else {
            targetDir = Path.of("/usr/local/share/ca-certificates");
            updateCmd = "update-ca-certificates";
        }
        String fileName = caCert.getFileName().toString();
        Path targetFile = targetDir.resolve(fileName);
        try {
            if (Files.isWritable(targetDir)) {
                Files.copy(caCert, targetFile, StandardCopyOption.REPLACE_EXISTING);
                CommandResult res = runSilently(List.of("/bin/sh", "-c", updateCmd));
                if (res.exitCode == 0) {
                    return new TrustResult(true, "Installed CA to " + targetFile + ".", null);
                }
            }
        } catch (IOException ignored) {
        }
        return new TrustResult(false, "Root privileges required to update the system trust store.",
                "sudo cp '" + caCert.toAbsolutePath() + "' '" + targetFile + "' && sudo " + updateCmd);
    }

    private static boolean isRhel() {
        return readOsRelease().contains("rhel") || readOsRelease().contains("fedora")
                || readOsRelease().contains("centos");
    }

    private static boolean isArch() {
        return readOsRelease().contains("arch");
    }

    private static @NotNull String readOsRelease() {
        try {
            return Files.readString(Path.of("/etc/os-release")).toLowerCase();
        } catch (IOException failure) {
            return "";
        }
    }

    private static @NotNull String caSubject(@NotNull Path caCert) {
        try {
            String base64 = Files.readString(caCert)
                    .replaceAll("-----BEGIN CERTIFICATE-----", "")
                    .replaceAll("-----END CERTIFICATE-----", "")
                    .replaceAll("\\s", "");
            byte[] der = java.util.Base64.getDecoder().decode(base64);
            java.security.cert.CertificateFactory factory =
                    java.security.cert.CertificateFactory.getInstance("X.509");
            java.security.cert.X509Certificate certificate =
                    (java.security.cert.X509Certificate) factory.generateCertificate(
                            new java.io.ByteArrayInputStream(der));
            String dn = certificate.getSubjectX500Principal().getName();
            for (String part : dn.split(",")) {
                String trimmed = part.trim();
                if (trimmed.startsWith("CN=")) {
                    return trimmed.substring(3);
                }
            }
            return dn;
        } catch (Exception failure) {
            return "mitmkit local CA";
        }
    }

    private static @NotNull CommandResult runSilently(@NotNull List<String> cmd) {
        try {
            Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            process.getOutputStream().close();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                try {
                    process.getInputStream().transferTo(output);
                } catch (IOException ignored) {
                }
            });
            reader.setDaemon(true);
            reader.start();
            boolean finished = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                reader.interrupt();
                return new CommandResult(-1, "Process timed out: " + String.join(" ", cmd));
            }
            reader.join(500);
            return new CommandResult(process.exitValue(), output.toString(StandardCharsets.UTF_8));
        } catch (Exception failure) {
            return new CommandResult(-1,
                    failure.getMessage() != null ? failure.getMessage() : failure.getClass().getSimpleName());
        }
    }

    public record TrustResult(boolean success, @NotNull String message, @Nullable String suggestedCommand) {}

    private record CommandResult(int exitCode, @NotNull String output) {}
}

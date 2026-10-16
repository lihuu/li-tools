package io.github.lihuu.certkit;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@Command(
    name = "certkit",
    mixinStandardHelpOptions = true,
    version = "certkit 0.1.0",
    description = "One command for SSL/TLS certificates without OpenSSL agony.",
    subcommands = {
        Main.GenCommand.class,
        Main.CaCommand.class,
        Main.CheckCommand.class,
        Main.ConvertCommand.class,
        Main.TrustCommand.class
    }
)
public final class Main implements Callable<Integer> {

    @Option(names = "--json", description = "Output results in structured JSON format")
    boolean json;

    @Option(names = "--quiet", description = "Suppress human-readable progress notes")
    boolean quiet;

    public static void main(String[] args) {
        int exitCode = run(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    public static int run(String... args) {
        CommandLine cmd = new CommandLine(new Main());
        return cmd.execute(args);
    }

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 0;
    }

    @Command(name = "gen", description = "Generate TLS certificate and private key with SAN")
    static class GenCommand implements Callable<Integer> {
        @ParentCommand
        private Main parent;

        @Parameters(description = "Hostnames and/or IP addresses (e.g. localhost 127.0.0.1 api.local)", arity = "0..*")
        private List<String> rawHosts;

        @Option(names = "--self-signed", description = "Force self-signed certificate, skipping local CA")
        private boolean selfSigned;

        @Option(names = "--days", defaultValue = "365", description = "Validity in days (default: 365)")
        private int days;

        @Option(names = "--algo", defaultValue = "ecdsa", description = "Key algorithm: ecdsa (P-256) or rsa4096 (default: ecdsa)")
        private String algo;

        @Option(names = "--out", description = "Output directory (default: current directory)")
        private Path outDir;

        @Option(names = "--ca-cert", description = "Custom CA certificate path (default: ~/.certkit/ca/ca.crt)")
        private Path customCaCert;

        @Option(names = "--ca-key", description = "Custom CA private key path (default: ~/.certkit/ca/ca.key)")
        private Path customCaKey;

        @Override
        public Integer call() {
            try {
                Path destDir = outDir != null ? outDir : Path.of(".");
                Path certOut = destDir.resolve("cert.pem");
                Path keyOut = destDir.resolve("key.pem");
                Path fullchainOut = destDir.resolve("fullchain.pem");

                var split = Defaults.splitHosts(rawHosts);
                Openssl openssl = new Openssl();

                Path activeCaCert = customCaCert;
                Path activeCaKey = customCaKey;
                boolean useCa = false;

                if (activeCaCert != null && activeCaKey != null) {
                    useCa = true;
                } else if (!selfSigned && Defaults.hasDefaultCa()) {
                    activeCaCert = Defaults.defaultCaCert();
                    activeCaKey = Defaults.defaultCaKey();
                    useCa = true;
                }

                CertInfo info;
                if (useCa) {
                    info = openssl.generateSignedByCa(split.domains(), split.ips(), days, algo, activeCaCert, activeCaKey, certOut, keyOut, fullchainOut);
                } else {
                    info = openssl.generateSelfSigned(split.domains(), split.ips(), days, algo, certOut, keyOut);
                }

                if (parent != null && parent.json) {
                    System.out.println(info.toJson());
                } else {
                    if (parent == null || !parent.quiet) {
                        System.out.println("✔ Certificate and private key generated successfully!");
                        System.out.println("  Certificate: " + certOut.toAbsolutePath().normalize());
                        System.out.println("  Private Key: " + keyOut.toAbsolutePath().normalize() + " (mode: 0600)");
                        if (useCa) {
                            System.out.println("  Fullchain:   " + fullchainOut.toAbsolutePath().normalize());
                            System.out.println("  Signed by:   " + activeCaCert.toAbsolutePath().normalize() + " (Trusted Root CA)");
                        } else if (!selfSigned) {
                            System.out.println();
                            System.out.println("💡 Note: Generated as self-signed certificate.");
                            System.out.println("   Run 'certkit ca' once to enable trusted root CA for green-lock browser support.");
                        }
                        System.out.println();
                        System.out.println(info.toHumanString());
                    }
                }
                return 0;
            } catch (Exception e) {
                System.err.println("certkit gen: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                return 1;
            }
        }
    }

    @Command(name = "ca", description = "Initialize local root CA and install into system trust store")
    static class CaCommand implements Callable<Integer> {
        @ParentCommand
        private Main parent;

        @Option(names = "--name", description = "CA Common Name (default: 'Local Development Root CA')")
        private String name;

        @Option(names = "--days", defaultValue = "3650", description = "Validity in days (default: 3650 / 10 years)")
        private int days;

        @Option(names = "--algo", defaultValue = "ecdsa", description = "Key algorithm: ecdsa (P-256) or rsa4096 (default: ecdsa)")
        private String algo;

        @Option(names = "--out", description = "Output directory (default: ~/.certkit/ca)")
        private Path outDir;

        @Option(names = "--no-trust", description = "Skip automatic installation into system trust store")
        private boolean noTrust;

        @Override
        public Integer call() {
            try {
                Path destDir = outDir != null ? outDir : Defaults.defaultCaDir();
                Path caCertOut = destDir.resolve("ca.crt");
                Path caKeyOut = destDir.resolve("ca.key");

                String caName = (name != null && !name.isBlank()) ? name : defaultCaName();

                Openssl openssl = new Openssl();
                CertInfo info = openssl.generateCa(caName, days, algo, caCertOut, caKeyOut);

                if (parent != null && parent.json) {
                    System.out.println(info.toJson());
                } else {
                    if (parent == null || !parent.quiet) {
                        System.out.println("✔ Root CA created successfully!");
                        System.out.println("  CA Certificate: " + caCertOut.toAbsolutePath().normalize());
                        System.out.println("  CA Private Key: " + caKeyOut.toAbsolutePath().normalize() + " (mode: 0600)");
                        System.out.println();
                        System.out.println(info.toHumanString());

                        if (!noTrust) {
                            System.out.println();
                            TrustStore ts = new TrustStore();
                            var res = ts.install(caCertOut, false);
                            if (res.success()) {
                                System.out.println("✔ " + res.message());
                            } else {
                                System.out.println("ℹ " + res.message());
                                if (res.suggestedCommand() != null) {
                                    System.out.println("  Run: " + res.suggestedCommand());
                                }
                            }
                        }
                    }
                }
                return 0;
            } catch (Exception e) {
                System.err.println("certkit ca: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                return 1;
            }
        }

        private String defaultCaName() {
            try {
                String host = InetAddress.getLocalHost().getHostName();
                return "Local Development Root CA (" + host + ")";
            } catch (UnknownHostException ignored) {
                return "Local Development Root CA";
            }
        }
    }

    @Command(name = "check", description = "Inspect local cert file or remote host:port")
    static class CheckCommand implements Callable<Integer> {
        @ParentCommand
        private Main parent;

        @Parameters(index = "0", arity = "0..1", description = "Local cert file or remote host (default: ./cert.pem)")
        private String rawTarget;

        @Option(names = "--timeout", defaultValue = "5000", description = "Network timeout in milliseconds for remote check (default: 5000)")
        private int timeout;

        @Override
        public Integer call() {
            try {
                String target = (rawTarget == null || rawTarget.isBlank()) ? "./cert.pem" : rawTarget;
                CertInfo info;
                Path path = Path.of(target);

                if (Files.exists(path)) {
                    info = CertParser.parse(path);
                } else if (target.contains(":") && !target.contains("/") && !target.contains("\\")) {
                    String[] parts = target.split(":", 2);
                    String host = parts[0];
                    int port = Integer.parseInt(parts[1]);
                    info = CertParser.parseRemote(host, port, timeout);
                } else if (target.endsWith(".pem") || target.endsWith(".crt") || target.endsWith(".cer") || target.contains("/") || target.contains("\\")) {
                    throw new IllegalArgumentException("Certificate file does not exist: " + target);
                } else {
                    // Try remote default port 443 if it looks like a remote domain
                    info = CertParser.parseRemote(target, 443, timeout);
                }

                if (parent != null && parent.json) {
                    System.out.println(info.toJson());
                } else {
                    System.out.println(info.toHumanString());
                }
                return 0;
            } catch (Exception e) {
                System.err.println("certkit check: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                return 1;
            }
        }
    }

    @Command(name = "convert", description = "Convert certificates between PEM, P12/PFX, and DER")
    static class ConvertCommand implements Callable<Integer> {
        @ParentCommand
        private Main parent;

        @Parameters(index = "0", description = "Input certificate file (e.g. cert.pem, bundle.p12)")
        private Path inFile;

        @Parameters(index = "1", description = "Output certificate file (e.g. cert.p12, restored.pem, cert.der)")
        private Path outFile;

        @Option(names = {"-k", "--key"}, description = "Matching private key file (defaults to auto-detecting key.pem in same directory)")
        private Path inKey;

        @Option(names = {"-p", "--pass"}, defaultValue = "changeit", description = "Passphrase for PKCS#12 bundle (default: changeit)")
        private String pass;

        @Override
        public Integer call() {
            try {
                Path activeKey = inKey;
                String outName = outFile.getFileName().toString().toLowerCase();

                // Convention: Auto-detect matching key if converting to .p12 and key not specified
                if ((outName.endsWith(".p12") || outName.endsWith(".pfx")) && activeKey == null) {
                    Path parentDir = inFile.getParent() != null ? inFile.getParent() : Path.of(".");
                    Path candidate1 = parentDir.resolve("key.pem");
                    Path candidate2 = parentDir.resolve(inFile.getFileName().toString().replaceFirst("\\.[^.]+$", "") + "-key.pem");
                    if (Files.exists(candidate1)) {
                        activeKey = candidate1;
                    } else if (Files.exists(candidate2)) {
                        activeKey = candidate2;
                    }
                }

                Openssl openssl = new Openssl();
                openssl.convert(inFile, activeKey, outFile, pass);
                if (parent == null || !parent.quiet) {
                    System.out.println("✔ Converted: " + inFile + " -> " + outFile);
                }
                return 0;
            } catch (Exception e) {
                System.err.println("certkit convert: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                return 1;
            }
        }
    }

    @Command(
        name = "trust",
        description = "Manage system trust store for custom root CA certificates",
        subcommands = {
            TrustInstallCommand.class,
            TrustCheckCommand.class,
            TrustUninstallCommand.class
        }
    )
    static class TrustCommand implements Callable<Integer> {
        @Override
        public Integer call() {
            CommandLine.usage(this, System.out);
            return 0;
        }
    }

    @Command(name = "install", description = "Install and trust a root CA in the operating system")
    static class TrustInstallCommand implements Callable<Integer> {
        @Parameters(index = "0", arity = "0..1", description = "Root CA cert path (default: ~/.certkit/ca/ca.crt)")
        private Path rawCaCert;

        @Option(names = "--system", description = "Install to system-wide keychain/truststore (requires admin/root)")
        private boolean system;

        @Override
        public Integer call() {
            try {
                Path caCert = rawCaCert != null ? rawCaCert : Defaults.defaultCaCert();
                TrustStore ts = new TrustStore();
                var result = ts.install(caCert, system);
                if (result.success()) {
                    System.out.println("✔ " + result.message());
                    return 0;
                } else {
                    System.err.println("✖ " + result.message());
                    if (result.suggestedCommand() != null) {
                        System.err.println("💡 Run with elevated privileges:");
                        System.err.println("   " + result.suggestedCommand());
                    }
                    return 1;
                }
            } catch (Exception e) {
                System.err.println("certkit trust install: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                return 1;
            }
        }
    }

    @Command(name = "check", description = "Check if a root CA is installed in the trust store")
    static class TrustCheckCommand implements Callable<Integer> {
        @Parameters(index = "0", arity = "0..1", description = "Root CA cert path (default: ~/.certkit/ca/ca.crt)")
        private Path rawCaCert;

        @Override
        public Integer call() {
            try {
                Path caCert = rawCaCert != null ? rawCaCert : Defaults.defaultCaCert();
                TrustStore ts = new TrustStore();
                var result = ts.check(caCert);
                System.out.println((result.success() ? "✔ " : "ℹ ") + result.message());
                return result.success() ? 0 : 1;
            } catch (Exception e) {
                System.err.println("certkit trust check: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                return 1;
            }
        }
    }

    @Command(name = "uninstall", description = "Remove a root CA from the trust store")
    static class TrustUninstallCommand implements Callable<Integer> {
        @Parameters(index = "0", arity = "0..1", description = "Root CA cert path (default: ~/.certkit/ca/ca.crt)")
        private Path rawCaCert;

        @Option(names = "--system", description = "Remove from system-wide keychain (requires admin/root)")
        private boolean system;

        @Override
        public Integer call() {
            try {
                Path caCert = rawCaCert != null ? rawCaCert : Defaults.defaultCaCert();
                TrustStore ts = new TrustStore();
                var result = ts.uninstall(caCert, system);
                if (result.success()) {
                    System.out.println("✔ " + result.message());
                    return 0;
                } else {
                    System.err.println("✖ " + result.message());
                    if (result.suggestedCommand() != null) {
                        System.err.println("💡 Run with elevated privileges:");
                        System.err.println("   " + result.suggestedCommand());
                    }
                    return 1;
                }
            } catch (Exception e) {
                System.err.println("certkit trust uninstall: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                return 1;
            }
        }
    }
}

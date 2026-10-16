package io.github.lihuu.certkit;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
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

        @Option(names = {"-d", "--domain"}, description = "Domain name(s) (e.g. localhost, api.local)", split = ",")
        private String[] domains = new String[]{"localhost"};

        @Option(names = "--ip", description = "IP SAN(s) (e.g. 127.0.0.1)", split = ",")
        private String[] ips = new String[]{"127.0.0.1"};

        @Option(names = "--days", defaultValue = "365", description = "Validity in days (default: 365)")
        private int days;

        @Option(names = "--algo", defaultValue = "ecdsa", description = "Key algorithm: ecdsa (P-256) or rsa4096 (default: ecdsa)")
        private String algo;

        @Option(names = "--out", defaultValue = "./certs", description = "Output directory (default: ./certs)")
        private Path outDir;

        @Option(names = "--ca-cert", description = "Path to CA certificate to sign this certificate")
        private Path caCert;

        @Option(names = "--ca-key", description = "Path to CA private key to sign this certificate")
        private Path caKey;

        @Override
        public Integer call() {
            try {
                Path certOut = outDir.resolve("cert.pem");
                Path keyOut = outDir.resolve("key.pem");
                Path fullchainOut = outDir.resolve("fullchain.pem");

                Openssl openssl = new Openssl();
                List<String> domainList = domains != null ? Arrays.asList(domains) : List.of("localhost");
                List<String> ipList = ips != null ? Arrays.asList(ips) : List.of("127.0.0.1");

                CertInfo info;
                if (caCert != null && caKey != null) {
                    info = openssl.generateSignedByCa(domainList, ipList, days, algo, caCert, caKey, certOut, keyOut, fullchainOut);
                } else {
                    info = openssl.generateSelfSigned(domainList, ipList, days, algo, certOut, keyOut);
                }

                if (parent != null && parent.json) {
                    System.out.println(info.toJson());
                } else {
                    if (parent == null || !parent.quiet) {
                        System.out.println("✔ Certificate and private key generated successfully!");
                        System.out.println("  Certificate: " + certOut.toAbsolutePath());
                        System.out.println("  Private Key: " + keyOut.toAbsolutePath() + " (mode: 0600)");
                        if (caCert != null) {
                            System.out.println("  Fullchain:   " + fullchainOut.toAbsolutePath());
                            System.out.println("  Signed by:   " + caCert.toAbsolutePath());
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

    @Command(name = "ca", description = "Generate a local root CA for trusted dev environments")
    static class CaCommand implements Callable<Integer> {
        @ParentCommand
        private Main parent;

        @Option(names = "--name", defaultValue = "Local Development Root CA", description = "CA Common Name (default: 'Local Development Root CA')")
        private String name;

        @Option(names = "--days", defaultValue = "3650", description = "Validity in days (default: 3650 / 10 years)")
        private int days;

        @Option(names = "--algo", defaultValue = "ecdsa", description = "Key algorithm: ecdsa (P-256) or rsa4096 (default: ecdsa)")
        private String algo;

        @Option(names = "--out", defaultValue = "./ca", description = "Output directory (default: ./ca)")
        private Path outDir;

        @Override
        public Integer call() {
            try {
                Path caCertOut = outDir.resolve("ca.crt");
                Path caKeyOut = outDir.resolve("ca.key");

                Openssl openssl = new Openssl();
                CertInfo info = openssl.generateCa(name, days, algo, caCertOut, caKeyOut);

                if (parent != null && parent.json) {
                    System.out.println(info.toJson());
                } else {
                    if (parent == null || !parent.quiet) {
                        System.out.println("✔ Root CA created successfully!");
                        System.out.println("  CA Certificate: " + caCertOut.toAbsolutePath());
                        System.out.println("  CA Private Key: " + caKeyOut.toAbsolutePath() + " (mode: 0600)");
                        System.out.println();
                        System.out.println(info.toHumanString());
                        System.out.println();
                        System.out.println("💡 Next step: Install into system trust store:");
                        System.out.println("   certkit trust install " + caCertOut);
                    }
                }
                return 0;
            } catch (Exception e) {
                System.err.println("certkit ca: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                return 1;
            }
        }
    }

    @Command(name = "check", description = "Inspect local cert file or remote host:port")
    static class CheckCommand implements Callable<Integer> {
        @ParentCommand
        private Main parent;

        @Parameters(index = "0", description = "Local cert file path or remote host:port (e.g. ./cert.pem or github.com:443)")
        private String target;

        @Option(names = "--timeout", defaultValue = "5000", description = "Network timeout in milliseconds for remote check (default: 5000)")
        private int timeout;

        @Override
        public Integer call() {
            try {
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

        @Option(names = "--in", required = true, description = "Input certificate file (e.g. cert.pem, bundle.p12)")
        private Path inFile;

        @Option(names = "--key", description = "Matching private key file (required when exporting to PKCS#12 / .p12)")
        private Path inKey;

        @Option(names = "--out", required = true, description = "Output file (e.g. cert.p12, cert.der, cert.pem)")
        private Path outFile;

        @Option(names = "--pass", defaultValue = "changeit", description = "Passphrase for PKCS#12 bundle (default: changeit)")
        private String pass;

        @Override
        public Integer call() {
            try {
                Openssl openssl = new Openssl();
                openssl.convert(inFile, inKey, outFile, pass);
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
        @ParentCommand
        private TrustCommand trustParent;

        @Parameters(index = "0", description = "Root CA certificate file (e.g. ./ca/ca.crt)")
        private Path caCert;

        @Option(names = "--system", description = "Install to system-wide keychain/truststore (requires admin/root)")
        private boolean system;

        @Override
        public Integer call() {
            try {
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
        @Parameters(index = "0", description = "Root CA certificate file (e.g. ./ca/ca.crt)")
        private Path caCert;

        @Override
        public Integer call() {
            try {
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
        @Parameters(index = "0", description = "Root CA certificate file (e.g. ./ca/ca.crt)")
        private Path caCert;

        @Option(names = "--system", description = "Remove from system-wide keychain (requires admin/root)")
        private boolean system;

        @Override
        public Integer call() {
            try {
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

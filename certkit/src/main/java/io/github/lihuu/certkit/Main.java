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
        Main.ConvertCommand.class
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

        @Override
        public Integer call() {
            try {
                Path certOut = outDir.resolve("cert.pem");
                Path keyOut = outDir.resolve("key.pem");

                Openssl openssl = new Openssl();
                List<String> domainList = domains != null ? Arrays.asList(domains) : List.of("localhost");
                List<String> ipList = ips != null ? Arrays.asList(ips) : List.of("127.0.0.1");

                CertInfo info = openssl.generateSelfSigned(domainList, ipList, days, algo, certOut, keyOut);

                if (parent != null && parent.json) {
                    System.out.println(info.toJson());
                } else {
                    if (parent == null || !parent.quiet) {
                        System.out.println("✔ Certificate and private key generated successfully!");
                        System.out.println("  Certificate: " + certOut.toAbsolutePath());
                        System.out.println("  Private Key: " + keyOut.toAbsolutePath() + " (mode: 0600)");
                        System.out.println();
                        System.out.println(info.toHumanString());
                    }
                }
                return 0;
            } catch (Exception e) {
                System.err.println("certkit: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
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

    @Command(name = "ca", description = "Generate a local root CA for trusted dev environments")
    static class CaCommand implements Callable<Integer> {
        @ParentCommand
        private Main parent;

        @Option(names = "--name", defaultValue = "Local Development Root CA", description = "CA Common Name")
        private String name;

        @Option(names = "--days", defaultValue = "3650", description = "Validity in days (default: 3650)")
        private int days;

        @Option(names = "--out", defaultValue = "./ca", description = "Output directory (default: ./ca)")
        private Path outDir;

        @Override
        public Integer call() {
            System.err.println("certkit: 'ca' subcommand is planned for implementation in next sprint.");
            return 0;
        }
    }

    @Command(name = "convert", description = "Convert certificates between PEM, P12/PFX, and DER")
    static class ConvertCommand implements Callable<Integer> {
        @ParentCommand
        private Main parent;

        @Option(names = "--in", required = true, description = "Input certificate file")
        private Path inFile;

        @Option(names = "--out", required = true, description = "Output certificate file")
        private Path outFile;

        @Override
        public Integer call() {
            System.err.println("certkit: 'convert' subcommand is planned for implementation in next sprint.");
            return 0;
        }
    }
}

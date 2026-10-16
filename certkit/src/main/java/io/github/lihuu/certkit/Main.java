package io.github.lihuu.certkit;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

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
    private boolean json;

    @Option(names = "--quiet", description = "Suppress human-readable progress notes")
    private boolean quiet;

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
        @Option(names = {"-d", "--domain"}, description = "Domain name(s) (e.g. localhost, api.local)", split = ",")
        private String[] domains = new String[]{"localhost"};

        @Option(names = "--ip", description = "IP SAN(s) (e.g. 127.0.0.1)", split = ",")
        private String[] ips = new String[]{"127.0.0.1"};

        @Option(names = "--days", defaultValue = "365", description = "Validity in days (default: 365)")
        private int days;

        @Option(names = "--out", defaultValue = "./certs", description = "Output directory (default: ./certs)")
        private String outDir;

        @Override
        public Integer call() {
            System.out.println("certkit gen: generating certificate for domains=" + String.join(",", domains));
            return 0;
        }
    }

    @Command(name = "ca", description = "Generate a local root CA for trusted dev environments")
    static class CaCommand implements Callable<Integer> {
        @Option(names = "--name", defaultValue = "Local Development Root CA", description = "CA Common Name")
        private String name;

        @Option(names = "--days", defaultValue = "3650", description = "Validity in days (default: 3650)")
        private int days;

        @Option(names = "--out", defaultValue = "./ca", description = "Output directory (default: ./ca)")
        private String outDir;

        @Override
        public Integer call() {
            System.out.println("certkit ca: creating root CA '" + name + "'");
            return 0;
        }
    }

    @Command(name = "check", description = "Inspect local cert file or remote host:port")
    static class CheckCommand implements Callable<Integer> {
        @CommandLine.Parameters(index = "0", description = "Cert file path or remote host:port")
        private String target;

        @Override
        public Integer call() {
            System.out.println("certkit check: inspecting " + target);
            return 0;
        }
    }

    @Command(name = "convert", description = "Convert certificates between PEM, P12/PFX, and DER")
    static class ConvertCommand implements Callable<Integer> {
        @Option(names = "--in", required = true, description = "Input certificate file")
        private String inFile;

        @Option(names = "--out", required = true, description = "Output certificate file")
        private String outFile;

        @Override
        public Integer call() {
            System.out.println("certkit convert: " + inFile + " -> " + outFile);
            return 0;
        }
    }
}

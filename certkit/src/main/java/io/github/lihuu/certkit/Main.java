package io.github.lihuu.certkit;

public final class Main {
    public static final String VERSION = "0.1.0";

    public static void main(String[] args) {
        int exitCode = run(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    public static int run(String... args) {
        if (args.length == 0 || "--help".equals(args[0]) || "-h".equals(args[0])) {
            printHelp();
            return 0;
        }

        if ("--version".equals(args[0]) || "-v".equals(args[0])) {
            System.out.println("certkit " + VERSION);
            return 0;
        }

        String subcommand = args[0];
        return switch (subcommand) {
            case "gen", "ca", "check", "convert" -> {
                System.err.println("certkit: subcommand '" + subcommand + "' is planned for implementation in next sprint.");
                yield 0;
            }
            default -> {
                System.err.println("certkit: unknown command '" + subcommand + "'. See 'certkit --help'.");
                yield 2;
            }
        };
    }

    private static void printHelp() {
        System.out.println("""
            certkit — One command for SSL/TLS certificates without OpenSSL agony.

            USAGE:
                certkit <command> [options]

            COMMANDS:
                gen        Generate TLS certificate and private key (with SAN)
                ca         Generate a local root CA for trusted dev environments
                check      Inspect local cert file or remote host:port
                convert    Convert certificates between PEM, P12/PFX, and DER

            GLOBAL OPTIONS:
                --json     Output results in structured JSON format
                --quiet    Suppress human-readable progress notes
                --help     Show this help message
                --version  Show certkit version
            """);
    }
}

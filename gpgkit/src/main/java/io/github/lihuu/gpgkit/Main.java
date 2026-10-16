package io.github.lihuu.gpgkit;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Command dispatch.
 *
 * <p>Dispatch is a switch over command names and nothing else. No reflection, no ServiceLoader,
 * no annotation scan: Native Image only keeps what it can see being reached, so a command
 * registered by name alone would compile, pass every JVM test, and be missing from the
 * executable. Adding a command means a case here and a class beside this one — both visible to
 * the compiler, both therefore in the image.
 */
public final class Main {
    private Main() {
    }

    private static final String USAGE = """
            gpgkit — one command for the whole life of a GPG key.

            usage: gpgkit [--json] [--quiet] [--help] <command> [arguments]

            global options:
              --json    machine-readable output on stdout
              --quiet   the short answer only: no explanations
              --help    this text

            exit codes:
              0  success
              1  failure; the reason is on stderr
              2  usage error

            commands:
              status                  list local keys: fingerprint, algorithm, created,
                                      expiry and days remaining, usage, whether the private
                                      key is here, keyserver visibility; ends with one verdict
              gen [--uid "Name <mail>"] [--expire 2y] [--algo rsa4096|ed25519]
                  [--passphrase-file F | --no-passphrase] [--publish]
              backup [--key <id|all>] [--out DIR]
                     [--passphrase-file F | --generate-passphrase-to F]
                     [--dest <spec>]...
              restore FILE [--passphrase-file F] [--keyring DIR] [--yes]
              extend [--key <id>] [--expire 2y] [--publish]
              sign <file>... [--checksums sha256] [--out DIR] [--key <id>]
              verify <file> [--sig <file.asc>] [--checksums FILE]
              publish [--key <id>] [--keyserver hkps://keyserver.ubuntu.com]
              doctor                  can this machine still sign, and still publish?""";

    public static void main(String[] args) {
        // The verdict lines are Chinese (SPEC §2 spells them out) and stdout's encoding follows
        // the locale, so under LANG=C they would silently become '?' — a corrupt answer that
        // looks like a working one. Pin both streams to UTF-8 before anything can print.
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
        System.exit(run(args));
    }

    static int run(String[] argv) {
        List<String> words = new ArrayList<>();
        boolean json = false;
        boolean quiet = false;
        boolean help = false;
        boolean positional = false;
        for (String argument : argv) {
            if (!positional) {
                if (argument.equals("--")) {
                    // Everything after -- is a value, never a flag. `gpgkit sign -- -odd-name` has
                    // to sign a file called "-odd-name" rather than complain about an option.
                    positional = true;
                    continue;
                }
                if (argument.equals("--json")) {
                    json = true;
                    continue;
                }
                if (argument.equals("--quiet")) {
                    quiet = true;
                    continue;
                }
                if (argument.equals("--help")) {
                    help = true;
                    continue;
                }
            }
            words.add(argument);
        }

        if (help) {
            System.out.println(USAGE);
            // --help wins over a bad command line: asking what the tool does is never an error.
            return Defaults.EXIT_OK;
        }
        if (words.isEmpty()) {
            System.err.println("gpgkit: no command given");
            System.err.println(USAGE);
            return Defaults.EXIT_USAGE;
        }

        GlobalOptions global = new GlobalOptions(json, quiet, help);
        String command = words.get(0);
        List<String> commandArguments = words.subList(1, words.size());

        int exitCode;
        try {
            exitCode = switch (command) {
                case "status" -> new StatusCommand(global).run(commandArguments);
                case "doctor" -> new DoctorCommand(global).run(commandArguments);
                case "gen" -> new GenCommand(global).run(commandArguments);
                case "backup" -> new BackupCommand(global).run(commandArguments);
                case "restore" -> new RestoreCommand(global).run(commandArguments);
                case "extend" -> new ExtendCommand(global).run(commandArguments);
                case "sign" -> new SignCommand(global).run(commandArguments);
                case "verify" -> new VerifyCommand(global).run(commandArguments);
                case "publish" -> new PublishCommand(global).run(commandArguments);
                default -> {
                    System.err.println("gpgkit: unknown command: " + command);
                    System.err.println(USAGE);
                    yield Defaults.EXIT_USAGE;
                }
            };
        } catch (Failure failure) {
            System.err.println("gpgkit: " + failure.getMessage());
            exitCode = Defaults.EXIT_FAILURE;
        }
        return exitCode;
    }
}

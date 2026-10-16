package io.github.lihuu.gpgkit;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * The one place in this project that starts a {@code gpg} process.
 *
 * <p>Everything gpg-facing goes through here so that one rule cannot be forgotten in one corner:
 * <b>no invocation may ever wait for a human.</b> A gpg call that opens pinentry when this tool
 * runs from a script does not fail — it hangs until it times out, which is the worst possible
 * outcome for a command whose whole job is to be safe to run unattended (SPEC §3.5).
 *
 * <p>Four independent guards, because each one alone has a way around it:
 *
 * <ul>
 *   <li>{@code --batch} — gpg never asks a question instead of proceeding.
 *   <li>{@code --yes} — the "file exists, overwrite?" class of question is answered up front.
 *   <li>{@code --no-tty} — gpg never opens the controlling terminal.
 *   <li>{@code --pinentry-mode loopback} — when a passphrase is needed and none was supplied,
 *       gpg fails immediately instead of launching pinentry. This is the guard that matters for
 *       a key that does have a passphrase, and it is why a locked key is an error here rather
 *       than a dialog.
 * </ul>
 *
 * <p>On top of those, stdin is always a pipe owned by this class: even if some future gpg decided
 * to prompt anyway, it would read EOF rather than the terminal.
 *
 * <p>{@code LC_ALL=C} is set for the child because callers match on gpg's own wording (for
 * example "No data" for a key a keyserver does not know). Matching a translated message is a bug
 * that only appears on someone else's laptop.
 */
final class Gpg {
    private static final List<String> NON_INTERACTIVE =
            List.of("--batch", "--yes", "--no-tty", "--pinentry-mode", "loopback");

    /**
     * Long enough for a slow smartcard, short enough that a stuck call does not become the
     * user's problem. Callers that expect a network round trip pass something shorter.
     */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    /** Exit code for "the process could not be started at all", which gpg itself never returns. */
    private static final int NEVER_STARTED = 127;

    private final Path executable;
    private final Path home;

    /** Uses the gpg found on PATH and the user's own keyring. */
    Gpg() {
        this(locate().orElse(null), null);
    }

    /** Uses the gpg found on PATH against a specific keyring, which is how a probe stays inert. */
    Gpg(Path home) {
        this(locate().orElse(null), home);
    }

    private Gpg(Path executable, Path home) {
        this.executable = executable;
        this.home = home;
    }

    /**
     * Finds gpg the way a shell would, by walking PATH.
     *
     * <p>Not by running {@code which}: that is one more process to start, and it answers a
     * different question when PATH is unusual. The absolute path is used for every call after
     * this, so a PATH that changes mid-run cannot swap the binary underneath us.
     */
    static Optional<Path> locate() {
        String path = System.getenv("PATH");
        if (path == null) {
            return Optional.empty();
        }
        for (String directory : path.split(File.pathSeparator)) {
            if (directory.isBlank()) {
                continue;
            }
            Path candidate = Path.of(directory, "gpg");
            if (Files.isExecutable(candidate) && !Files.isDirectory(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    boolean available() {
        return executable != null;
    }

    /** The absolute path of the gpg this instance will run, or null when there is none. */
    Path executable() {
        return executable;
    }

    Gpg.Result run(List<String> arguments) {
        return run(arguments, null, DEFAULT_TIMEOUT);
    }

    Gpg.Result run(List<String> arguments, byte[] stdin) {
        return run(arguments, stdin, DEFAULT_TIMEOUT);
    }

    /**
     * Runs gpg and returns everything it produced.
     *
     * <p>A timeout is not an exception: the caller is usually in the middle of building a report,
     * and "this check could not be completed" is a line in that report, not the end of the
     * command. The process is killed and the result carries a {@link #NEVER_STARTED} exit code and
     * a note on stderr, so a caller that only looks at {@code ok()} gets the right answer.
     */
    Gpg.Result run(List<String> arguments, byte[] stdin, Duration timeout) {
        if (executable == null) {
            return new Gpg.Result(NEVER_STARTED, new byte[0], "gpg was not found on PATH");
        }

        List<String> command = new ArrayList<>(arguments.size() + NON_INTERACTIVE.size() + 1);
        command.add(executable.toString());
        command.addAll(NON_INTERACTIVE);
        command.addAll(arguments);

        ProcessBuilder builder = new ProcessBuilder(command);
        Map<String, String> environment = builder.environment();
        environment.put("LC_ALL", "C");
        if (home != null) {
            environment.put("GNUPGHOME", home.toString());
        }

        Process process;
        try {
            process = builder.start();
        } catch (IOException failure) {
            return new Gpg.Result(NEVER_STARTED, new byte[0], "could not start gpg: " + failure.getMessage());
        }

        // stdout is read on a virtual thread while this thread reads stderr. Reading one and
        // letting the other fill its pipe buffer is the classic way to deadlock a child process:
        // gpg blocks writing, and we block waiting for it to exit.
        byte[][] stdout = new byte[1][];
        Thread stdoutReader = Thread.ofVirtual().start(() -> {
            try (InputStream stream = process.getInputStream()) {
                stdout[0] = stream.readAllBytes();
            } catch (IOException failure) {
                stdout[0] = new byte[0];
            }
        });

        Thread stdinWriter = Thread.ofVirtual().start(() -> {
            try (OutputStream stream = process.getOutputStream()) {
                if (stdin != null) {
                    stream.write(stdin);
                }
            } catch (IOException ignored) {
                // gpg deciding not to read its input is not an error; the exit code says what
                // happened, and a broken pipe here would only hide it.
            }
        });

        byte[] stderr;
        try (InputStream stream = process.getErrorStream()) {
            stderr = stream.readAllBytes();
        } catch (IOException failure) {
            stderr = new byte[0];
        }

        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            finished = false;
        }

        if (!finished) {
            process.destroyForcibly();
            try {
                process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        join(stdoutReader);
        join(stdinWriter);

        String text = new String(stderr, StandardCharsets.UTF_8);
        if (!finished) {
            text = text + "\ngpg did not finish within " + timeout.toSeconds() + "s and was killed";
        }
        return new Gpg.Result(finished ? process.exitValue() : NEVER_STARTED, stdout[0], text);
    }

    /**
     * Waits for a reader thread, but not for ever.
     *
     * <p>The bound is only ever reached when the child started a daemon that inherited the pipe
     * and outlived it, in which case that pipe sees no end-of-file and the read blocks. Two
     * seconds is long enough for a reader that is merely behind, and short enough that one stuck
     * pipe does not become the user's wait.
     */
    private static void join(Thread thread) {
        try {
            thread.join(Duration.ofSeconds(2));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * What a gpg call produced.
     *
     * <p>{@code stdout} stays raw bytes: {@code --export} produces a binary key block and
     * decoding it as text would corrupt it. Callers that know their output is text use
     * {@link #out()}.
     */
    record Result(int exitCode, byte[] stdout, String stderr) {
        boolean ok() {
            return exitCode == 0;
        }

        String out() {
            return new String(stdout, StandardCharsets.UTF_8);
        }

        /**
         * The one line of stderr worth repeating in a report: the last thing gpg said.
         *
         * <p>Not the first. gpg narrates as it goes ("keybox created", "trustdb created") and puts
         * the reason it gave up at the end, so the first line is reliably the least interesting
         * one in the buffer.
         */
        String reason() {
            String[] lines = stderr.split("\n");
            for (int index = lines.length - 1; index >= 0; index--) {
                String trimmed = lines[index].strip();
                if (!trimmed.isEmpty()) {
                    return trimmed;
                }
            }
            return "gpg exited " + exitCode + " without an explanation";
        }
    }
}

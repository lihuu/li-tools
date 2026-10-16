package io.github.lihuu.gpgkit;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * The user id {@code gen} falls back to when {@code --uid} is not given (SPEC §2).
 *
 * <p>Read from {@code git config} rather than guessed from {@code $USER} or the system account
 * name, because the point of the default is to be the identity this person already signs with.
 * A key whose user id says "Someone Else" and whose commits say "someone@example.com" is a key
 * nobody can connect to anything.
 *
 * <p>{@code git config --get} walks the same files git itself would — system, global, and the
 * repository containing the current directory — so whatever identity commits use here is what
 * the key gets.
 *
 * <p>Failure to find one is not an error here: {@link #userId} returns empty and the caller
 * decides, because "git is not installed" and "you did not configure an email" need different
 * sentences and only the caller knows it is about to generate a key.
 */
final class GitIdentity {
    /** Short: this runs before a key is generated, and git answers from a local file. */
    private static final int TIMEOUT_SECONDS = 10;

    private GitIdentity() {
    }

    /** {@code "Name <mail>"}, or empty when git or either half of the identity is missing. */
    static Optional<String> userId() {
        String name = read("user.name");
        String email = read("user.email");
        if (name == null || email == null) {
            return Optional.empty();
        }
        return Optional.of(name + " <" + email + ">");
    }

    private static String read(String key) {
        List<String> command = List.of("git", "config", "--get", key);
        ProcessBuilder builder = new ProcessBuilder(command);
        // git prints what is in the file, byte for byte, so no locale is imposed on it: pinning
        // LC_ALL=C here would not corrupt a name, but it would make the encoding of the result
        // depend on which of the two settings happened to be in effect. The bytes are UTF-8 in
        // the configuration file and are decoded as UTF-8 below.
        Process process;
        try {
            process = builder.start();
        } catch (IOException missingGit) {
            return null;
        }

        byte[][] stdout = new byte[1][];
        Thread reader = Thread.ofVirtual().start(() -> {
            try (InputStream stream = process.getInputStream()) {
                stdout[0] = stream.readAllBytes();
            } catch (IOException failure) {
                stdout[0] = new byte[0];
            }
        });
        // stderr has to be drained too, even though nothing reads it: a child that writes more
        // than a pipe buffer holds blocks on the write, and the wait below would then never end.
        Thread errors = Thread.ofVirtual().start(() -> {
            try (InputStream stream = process.getErrorStream()) {
                stream.readAllBytes();
            } catch (IOException ignored) {
                // Nothing to say about it; the exit code is the answer we use.
            }
        });

        boolean finished;
        try {
            finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            finished = false;
        }
        if (!finished) {
            process.destroyForcibly();
        }
        join(reader);
        join(errors);

        if (!finished || process.exitValue() != 0) {
            // `git config --get` exits 1 for "this key is not set", which is the ordinary way to
            // answer "no"; it is not an error worth a message.
            return null;
        }
        String value = new String(stdout[0], StandardCharsets.UTF_8).strip();
        return value.isEmpty() ? null : value;
    }

    private static void join(Thread thread) {
        try {
            thread.join(2_000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Whether a {@code git} executable is on PATH at all, so a failure can say which problem it is. */
    static boolean available() {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String directory : path.split(java.io.File.pathSeparator)) {
            if (directory.isBlank()) {
                continue;
            }
            Path candidate = Path.of(directory, "git");
            if (Files.isExecutable(candidate) && !Files.isDirectory(candidate)) {
                return true;
            }
        }
        return false;
    }
}

package io.github.lihuu.gpgkit;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The CLI as a person runs it: in its own process, with the keyring and the PATH the test chose.
 *
 * <p>A child process rather than a call to {@link Main#run}, for two reasons that both matter here.
 * The commands read {@code GNUPGHOME} — and a real keyring is the one thing none of these tests may
 * touch — so the environment has to be set before the JVM starts. And the subject of several of
 * these tests is the exit code, which {@code main} produces with {@code System.exit}; calling the
 * dispatch method would test a return value instead of the thing a shell sees.
 *
 * <p>The child is given {@code target/classes} and nothing else. It is the same artifact the native
 * executable is built from, and it means the test classpath — JUnit and its dependencies — can never
 * be what makes a test pass.
 */
final class Cli {
    /** Generous: two of these tests generate an RSA-4096 key. */
    private static final int TIMEOUT_SECONDS = 120;

    private final Path workingDirectory;
    private final Path gnupgHome;
    private final Path pathPrefix;
    private final Map<String, String> environment;

    Cli(Path workingDirectory, Path gnupgHome) {
        this(workingDirectory, gnupgHome, null, Map.of());
    }

    /**
     * @param pathPrefix  a directory to put in front of PATH, so a test can put its own {@code gpg}
     *                    there — the only way to observe what this tool asks a keyserver to do
     *                    without asking a keyserver anything
     * @param environment extra variables for the child
     */
    Cli(Path workingDirectory, Path gnupgHome, Path pathPrefix, Map<String, String> environment) {
        this.workingDirectory = workingDirectory;
        this.gnupgHome = gnupgHome;
        this.pathPrefix = pathPrefix;
        this.environment = environment;
    }

    record Result(int exitCode, String stdout, String stderr) {
        /** Everything the command said, in the order a terminal would have shown it. */
        String combined() {
            return stdout + stderr;
        }
    }

    Result run(String... arguments) {
        return run(List.of(arguments));
    }

    Result run(List<String> arguments) {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(mainClasses().toString());
        command.add("io.github.lihuu.gpgkit.Main");
        command.addAll(arguments);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDirectory.toFile());
        Map<String, String> childEnvironment = builder.environment();
        childEnvironment.put("GNUPGHOME", gnupgHome.toString());
        if (pathPrefix != null) {
            childEnvironment.put("PATH",
                    pathPrefix + File.pathSeparator + childEnvironment.getOrDefault("PATH", ""));
        }
        childEnvironment.putAll(environment);

        Process process;
        try {
            process = builder.start();
        } catch (IOException failure) {
            throw new IllegalStateException("could not start the CLI", failure);
        }

        byte[][] stdout = new byte[1][];
        byte[][] stderr = new byte[1][];
        Thread out = Thread.ofVirtual().start(() -> stdout[0] = drain(process.getInputStream()));
        Thread err = Thread.ofVirtual().start(() -> stderr[0] = drain(process.getErrorStream()));

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
        join(out);
        join(err);
        if (!finished) {
            throw new IllegalStateException("the CLI did not finish within " + TIMEOUT_SECONDS + "s");
        }
        return new Result(process.exitValue(),
                new String(stdout[0], StandardCharsets.UTF_8),
                new String(stderr[0], StandardCharsets.UTF_8));
    }

    /**
     * Where the classes under test live.
     *
     * <p>Derived from the build directory rather than read from {@code java.class.path}, which under
     * Surefire can be a small booter jar whose manifest names the real classpath — a detail that
     * would make the child's classpath depend on which Surefire version ran the tests.
     */
    private static Path mainClasses() {
        Path basedir = Path.of(System.getProperty("basedir", ".")).toAbsolutePath();
        Path classes = basedir.resolve("target").resolve("classes");
        if (!java.nio.file.Files.isDirectory(classes)) {
            throw new IllegalStateException("no compiled classes at " + classes
                    + "; the tests run after the compile phase and cannot run without them");
        }
        return classes;
    }

    private static byte[] drain(InputStream stream) {
        try (InputStream closing = stream) {
            return closing.readAllBytes();
        } catch (IOException failure) {
            return new byte[0];
        }
    }

    private static void join(Thread thread) {
        try {
            thread.join(5_000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}

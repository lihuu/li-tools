package io.github.lihuu.skillkit.core;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class GitClient {
    private GitClient() {}

    static @NotNull String run(@NotNull Path directory, @NotNull List<String> arguments) throws IOException {
        List<String> command = new ArrayList<>(List.of("git", "-c", "core.longpaths=true"));
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        Process process = builder.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        // Drain a pipe rather than deleting a redirected log that Git helpers may still hold on Windows.
        try (ExecutorService reader = Executors.newVirtualThreadPerTaskExecutor(); InputStream stream = process.getInputStream()) {
            Future<String> output = reader.submit(() -> {
                byte[] bytes = stream.readNBytes(65537);
                stream.transferTo(OutputStream.nullOutputStream());
                return bytes.length > 65536 ? "" : new String(bytes, StandardCharsets.UTF_8).strip();
            });
            try {
                if (!process.waitFor(90, TimeUnit.SECONDS)) throw new TimeoutException();
                String result = output.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (process.exitValue() != 0) throw new IOException("Git operation failed (exit " + process.exitValue() + ")");
                return result;
            } catch (InterruptedException failure) {
                stop(process);
                Thread.currentThread().interrupt();
                throw new IOException("Git operation interrupted", failure);
            } catch (TimeoutException failure) {
                stop(process);
                throw new IOException("Git operation timed out", failure);
            } catch (ExecutionException failure) {
                if (failure.getCause() instanceof IOException cause) throw cause;
                throw new IllegalStateException("Could not read Git output", failure.getCause());
            } finally {
                if (process.isAlive()) stop(process);
            }
        }
    }

    private static void stop(Process process) {
        List<ProcessHandle> descendants = process.descendants().toList();
        for (ProcessHandle child : descendants) child.destroyForcibly();
        process.destroyForcibly();
        process.onExit().join();
        for (ProcessHandle child : descendants) child.onExit().join();
    }
}

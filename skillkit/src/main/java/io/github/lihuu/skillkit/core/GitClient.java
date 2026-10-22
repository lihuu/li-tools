package io.github.lihuu.skillkit.core;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class GitClient {
    private GitClient() {}

    static @NotNull String run(@NotNull Path directory, @NotNull List<String> arguments) throws IOException {
        Path output = Files.createTempFile(directory, ".git-output-", ".log");
        Process process = null;
        try {
            List<String> command = new ArrayList<>(List.of("git"));
            command.addAll(arguments);
            ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile())
                    .redirectErrorStream(true).redirectOutput(output.toFile());
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            process = builder.start();
            if (!process.waitFor(90, TimeUnit.SECONDS)) {
                stop(process);
                throw new IOException("Git operation timed out");
            }
            if (process.exitValue() != 0) throw new IOException("Git operation failed (exit " + process.exitValue() + ")");
            if (Files.size(output) > 65536) return "";
            return Files.readString(output).strip();
        } catch (InterruptedException failure) {
            if (process != null) stop(process);
            Thread.currentThread().interrupt();
            throw new IOException("Git operation interrupted", failure);
        } finally {
            Files.deleteIfExists(output);
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

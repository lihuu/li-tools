package io.github.lihuu.skillkit.core;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class ParallelTasks {
    private ParallelTasks() {}

    @FunctionalInterface
    public interface Task<T> { @NotNull T run() throws IOException; }

    public record Outcome<T>(@Nullable T value, @Nullable String error) {}

    public static <T> @NotNull List<Outcome<T>> run(@NotNull List<Task<T>> tasks, int jobs) throws IOException {
        if (jobs < 1) throw new IllegalArgumentException("--jobs must be positive");
        List<Outcome<T>> results = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(jobs, Thread.ofVirtual().factory())) {
            List<Future<Outcome<T>>> futures = new ArrayList<>();
            for (Task<T> task : tasks) futures.add(executor.submit(() -> {
                try {
                    return new Outcome<>(task.run(), null);
                } catch (IOException | IllegalArgumentException failure) {
                    return new Outcome<T>(null, failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
                }
            }));
            try {
                for (Future<Outcome<T>> future : futures) results.add(future.get());
            } catch (InterruptedException failure) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
                throw new IOException("Skill operation interrupted", failure);
            } catch (ExecutionException failure) {
                executor.shutdownNow();
                throw new IOException("Skill task failed unexpectedly", failure.getCause());
            }
        }
        return results;
    }
}

package io.github.lihuu.skillkit.core;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public final class OperationLock implements AutoCloseable {
    private static final ConcurrentHashMap<Path, ReentrantLock> LOCAL = new ConcurrentHashMap<>();
    private final ReentrantLock local;
    private final FileChannel channel;
    private final FileLock lock;

    private OperationLock(ReentrantLock local, FileChannel channel, FileLock lock) {
        this.local = local;
        this.channel = channel;
        this.lock = lock;
    }

    public static @NotNull OperationLock acquire(@NotNull Path directory) throws IOException {
        Files.createDirectories(directory);
        Path key = directory.toRealPath().resolve(".skillkit.lock");
        ReentrantLock local = LOCAL.computeIfAbsent(key, ignored -> new ReentrantLock());
        local.lock();
        try {
            FileChannel channel = FileChannel.open(key, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try {
                return new OperationLock(local, channel, channel.lock());
            } catch (IOException | RuntimeException failure) {
                channel.close();
                throw failure;
            }
        } catch (IOException | RuntimeException failure) {
            local.unlock();
            throw failure;
        }
    }

    @Override
    public void close() throws IOException {
        try (channel) {
            lock.release();
        } finally {
            local.unlock();
        }
    }
}

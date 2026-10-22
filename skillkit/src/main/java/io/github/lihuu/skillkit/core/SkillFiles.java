package io.github.lihuu.skillkit.core;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

public final class SkillFiles {
    private SkillFiles() {}

    public static @NotNull Path inside(@NotNull Path root, @NotNull String relative) throws IOException {
        Path normalized = root.toAbsolutePath().normalize();
        Path path = normalized.resolve(relative).normalize();
        if (Path.of(relative).isAbsolute() || !path.startsWith(normalized)) {
            throw new IOException("Path escapes source: " + relative);
        }
        Path current = path;
        while (current != null && current.startsWith(normalized)) {
            if (Files.isSymbolicLink(current)) throw new IOException("Symbolic source path is not supported: " + current);
            current = current.getParent();
        }
        return path;
    }

    public static void copy(@NotNull Path source, @NotNull Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (!directory.equals(source) && directory.getFileName().toString().equals(".git")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(target.resolve(source.relativize(directory)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (!attributes.isRegularFile()) throw new IOException("Non-regular source file: " + file);
                Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public static void delete(@NotNull Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public static @NotNull String digest(@NotNull Path root) throws IOException {
        MessageDigest digest = sha256();
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> files = paths.filter(path -> !path.equals(root)).sorted().toList();
            for (Path file : files) {
                if (Files.isSymbolicLink(file)) throw new IOException("Symbolic source file: " + file);
                digest.update(root.relativize(file).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                if (Files.isRegularFile(file)) {
                    digest.update((byte) 1);
                    try (java.io.InputStream input = Files.newInputStream(file)) {
                        byte[] buffer = new byte[8192];
                        int count;
                        while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
                    }
                } else digest.update((byte) 2);
                digest.update((byte) 0);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static @NotNull String key(@NotNull String text) {
        return HexFormat.of().formatHex(sha256().digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("JDK has no SHA-256", failure);
        }
    }
}

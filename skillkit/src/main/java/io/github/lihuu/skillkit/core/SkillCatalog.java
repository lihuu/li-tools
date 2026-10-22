package io.github.lihuu.skillkit.core;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class SkillCatalog {
    private SkillCatalog() {}

    public record Skill(@NotNull String name, @NotNull Path directory) {}

    public static void validateName(@NotNull String name) {
        if (!name.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("Invalid skill name: " + name);
        }
    }

    public static @NotNull List<Skill> discover(@NotNull Path root) throws IOException {
        List<Skill> skills = new ArrayList<>();
        if (!Files.isDirectory(root)) return skills;
        Path search = Files.isDirectory(root.resolve("skills")) ? root.resolve("skills") : root;
        Files.walkFileTree(search, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                String name = directory.getFileName().toString();
                return name.equals(".git") || name.equals(".skillkit")
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (file.getFileName().toString().equals("SKILL.md") && attributes.isRegularFile()) {
                    Path directory = file.getParent();
                    skills.add(new Skill(directory.getFileName().toString(), directory.toAbsolutePath().normalize()));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        skills.sort(Comparator.comparing(Skill::name).thenComparing(skill -> skill.directory().toString()));
        return List.copyOf(skills);
    }

    public static @NotNull Skill select(@NotNull List<Skill> skills, @NotNull String name) {
        validateName(name);
        List<Skill> matches = skills.stream().filter(skill -> skill.name().equals(name)).toList();
        if (matches.size() != 1) {
            throw new IllegalArgumentException(matches.isEmpty() ? "Skill not found: " + name
                    : "Ambiguous skill " + name + ": " + matches.stream().map(Skill::directory).toList());
        }
        return matches.getFirst();
    }
}

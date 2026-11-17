package io.github.lihuu.dukit.core.scan;

import org.jetbrains.annotations.NotNull;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;

/**
 * The compiled form of {@code --exclude} patterns: the same rule the scan applies to entries,
 * usable afterwards by anything that looks at the filesystem again for the same scan.
 *
 * <p>A pattern without a {@code /} matches any path component; a pattern containing one is
 * matched against the path relative to the scan root. Keeping that split here means the scan
 * and the browser's on-demand file listing cannot drift apart about what the user asked to
 * skip.
 */
public final class Exclusions {
    private static final Exclusions NONE = new Exclusions(List.of(), List.of());

    private final List<PathMatcher> nameMatchers;
    private final List<PathMatcher> relativeMatchers;

    private Exclusions(@NotNull List<PathMatcher> nameMatchers, @NotNull List<PathMatcher> relativeMatchers) {
        this.nameMatchers = nameMatchers;
        this.relativeMatchers = relativeMatchers;
    }

    public static @NotNull Exclusions none() {
        return NONE;
    }

    public static @NotNull Exclusions compile(@NotNull List<String> patterns) {
        return compile(patterns, FileSystems.getDefault());
    }

    public static @NotNull Exclusions compile(@NotNull List<String> patterns, @NotNull FileSystem fileSystem) {
        if (patterns.isEmpty()) {
            return NONE;
        }
        List<PathMatcher> byName = new ArrayList<>();
        List<PathMatcher> byRelativePath = new ArrayList<>();
        for (String pattern : patterns) {
            PathMatcher matcher = fileSystem.getPathMatcher("glob:" + pattern);
            if (pattern.indexOf('/') >= 0) {
                byRelativePath.add(matcher);
            } else {
                byName.add(matcher);
            }
        }
        return new Exclusions(List.copyOf(byName), List.copyOf(byRelativePath));
    }

    public boolean isEmpty() {
        return nameMatchers.isEmpty() && relativeMatchers.isEmpty();
    }

    /**
     * @param root absolute scan root the relative patterns are anchored at
     * @param path absolute path being tested
     */
    public boolean matches(@NotNull Path root, @NotNull Path path) {
        if (isEmpty()) {
            return false;
        }
        Path name = path.getFileName();
        if (name != null) {
            for (PathMatcher matcher : nameMatchers) {
                if (matcher.matches(name)) {
                    return true;
                }
            }
        }
        for (PathMatcher matcher : relativeMatchers) {
            if (matcher.matches(root.relativize(path))) {
                return true;
            }
        }
        return false;
    }
}

package io.github.lihuu.skillkit.core;

import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class AgentPaths {
    private AgentPaths() {}

    public static @NotNull List<Path> targets(@NotNull Path home, @NotNull Map<String, String> environment) {
        LinkedHashSet<Path> roots = new LinkedHashSet<>(List.of(
                home.resolve(".agents/skills"),
                Path.of(environment.getOrDefault("CLAUDE_CONFIG_DIR", home.resolve(".claude").toString())).resolve("skills"),
                home.resolve(".qwen/skills"),
                home.resolve(".cursor/skills"),
                home.resolve(".gemini/skills"),
                Path.of(environment.getOrDefault("HERMES_HOME", home.resolve(".hermes").toString())).resolve("skills"),
                Path.of(environment.getOrDefault("XDG_CONFIG_HOME", home.resolve(".config").toString())).resolve("opencode/skills"),
                home.resolve(".gemini/antigravity/skills")
        ));
        return roots.stream().map(path -> path.toAbsolutePath().normalize()).distinct().sorted().toList();
    }
}

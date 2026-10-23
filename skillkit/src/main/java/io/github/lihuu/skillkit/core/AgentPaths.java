package io.github.lihuu.skillkit.core;

import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class AgentPaths {
    private AgentPaths() {}

    public static @NotNull List<Path> targets(@NotNull Path home, @NotNull Map<String, String> environment) {
        return targets(home, environment, List.of("shared", "claude-code", "qwen-code", "cursor", "gemini-cli", "hermes", "opencode", "antigravity"));
    }

    public static @NotNull List<Path> targets(@NotNull Path home, @NotNull Map<String, String> environment,
                                             @NotNull List<String> agents) {
        List<String> selected = agents.isEmpty() ? List.of("shared") : agents;
        return selected.stream().map(agent -> switch (agent) {
            case "shared", "codex", "pi", "zcode" -> home.resolve(".agents/skills");
            case "claude-code" -> Path.of(environment.getOrDefault("CLAUDE_CONFIG_DIR", home.resolve(".claude").toString())).resolve("skills");
            case "qwen-code" -> home.resolve(".qwen/skills");
            case "cursor" -> home.resolve(".cursor/skills");
            case "gemini-cli" -> home.resolve(".gemini/skills");
            case "hermes", "hermes-agent" -> Path.of(environment.getOrDefault("HERMES_HOME", home.resolve(".hermes").toString())).resolve("skills");
            case "opencode" -> Path.of(environment.getOrDefault("XDG_CONFIG_HOME", home.resolve(".config").toString())).resolve("opencode/skills");
            case "antigravity" -> home.resolve(".gemini/antigravity/skills");
            default -> throw new IllegalArgumentException("Unknown agent: " + agent + "; choose shared, codex, pi, zcode, claude-code, qwen-code, cursor, gemini-cli, hermes, opencode or antigravity");
        }).map(path -> path.toAbsolutePath().normalize()).distinct().sorted().toList();
    }
}

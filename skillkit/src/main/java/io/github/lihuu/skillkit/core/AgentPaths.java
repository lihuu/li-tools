package io.github.lihuu.skillkit.core;

import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class AgentPaths {
    private AgentPaths() {}

    public static @NotNull List<Path> targets(@NotNull List<String> agents, boolean projectScope,
            @NotNull Path home, @NotNull Path project, @NotNull Map<String, String> environment) {
        List<String> selected = new ArrayList<>(agents);
        if (selected.isEmpty() || selected.contains("*")) {
            selected.clear();
            for (String agent : List.of("codex", "claude-code", "cursor", "gemini-cli", "qwen-code", "hermes-agent", "zcode", "pi", "opencode", "antigravity")) {
                String marker = switch (agent) {
                    case "codex" -> ".codex";
                    case "claude-code" -> ".claude";
                    case "gemini-cli", "antigravity" -> ".gemini";
                    case "qwen-code" -> ".qwen";
                    case "hermes-agent" -> ".hermes";
                    case "opencode" -> ".config/opencode";
                    default -> "." + agent;
                };
                Path detection = switch (agent) {
                    case "codex" -> Path.of(environment.getOrDefault("CODEX_HOME", home.resolve(marker).toString()));
                    case "claude-code" -> Path.of(environment.getOrDefault("CLAUDE_CONFIG_DIR", home.resolve(marker).toString()));
                    case "hermes-agent" -> Path.of(environment.getOrDefault("HERMES_HOME", home.resolve(marker).toString()));
                    default -> home.resolve(marker);
                };
                if (Files.isDirectory(detection)) selected.add(agent);
            }
            if (selected.isEmpty()) throw new IllegalArgumentException("No supported agents detected; specify --agent (e.g. codex or universal)");
        }
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        for (String agent : selected) {
            Path root = switch (agent) {
                case "codex", "pi", "zcode", "universal" -> (projectScope ? project : home).resolve(".agents/skills");
                case "claude-code" -> projectScope ? project.resolve(".claude/skills")
                        : Path.of(environment.getOrDefault("CLAUDE_CONFIG_DIR", home.resolve(".claude").toString())).resolve("skills");
                case "qwen", "qwen-code" -> (projectScope ? project : home).resolve(".qwen/skills");
                case "cursor" -> projectScope ? project.resolve(".agents/skills") : home.resolve(".cursor/skills");
                case "gemini-cli" -> projectScope ? project.resolve(".agents/skills") : home.resolve(".gemini/skills");
                case "hermes-agent" -> projectScope ? project.resolve(".hermes/skills")
                        : Path.of(environment.getOrDefault("HERMES_HOME", home.resolve(".hermes").toString())).resolve("skills");
                case "opencode" -> projectScope ? project.resolve(".agents/skills")
                        : Path.of(environment.getOrDefault("XDG_CONFIG_HOME", home.resolve(".config").toString())).resolve("opencode/skills");
                case "antigravity" -> projectScope ? project.resolve(".agents/skills") : home.resolve(".gemini/antigravity/skills");
                default -> throw new IllegalArgumentException("Unsupported agent: " + agent);
            };
            roots.add(root.toAbsolutePath().normalize());
        }
        return roots.stream().sorted().toList();
    }
}

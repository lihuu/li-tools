package io.github.lihuu.tokenkit.cli;

import java.io.PrintWriter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import io.github.lihuu.tokenkit.core.model.Profile;
import io.github.lihuu.tokenkit.core.storage.SqliteTokenRepository;
import org.jetbrains.annotations.NotNull;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "profile", description = "Manage environment profiles", mixinStandardHelpOptions = true)
public final class ProfileCommand implements Callable<Integer> {
    private final SqliteTokenRepository repository;
    private final OutputStyle outputStyle;
    private final PrintWriter out;
    private final PrintWriter err;

    public ProfileCommand(
            @NotNull SqliteTokenRepository repository,
            @NotNull OutputStyle outputStyle,
            @NotNull PrintWriter out,
            @NotNull PrintWriter err
    ) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.outputStyle = Objects.requireNonNull(outputStyle, "outputStyle must not be null");
        this.out = Objects.requireNonNull(out, "out must not be null");
        this.err = Objects.requireNonNull(err, "err must not be null");
    }

    public static CommandLine createCommandLine(
            @NotNull SqliteTokenRepository repository,
            @NotNull OutputStyle outputStyle,
            @NotNull PrintWriter out,
            @NotNull PrintWriter err
    ) {
        ProfileCommand profileCmd = new ProfileCommand(repository, outputStyle, out, err);
        CommandLine cmd = new CommandLine(profileCmd);
        cmd.addSubcommand("list", new ListSubcommand(repository, outputStyle, out, err));
        cmd.addSubcommand("create", new CreateSubcommand(repository, outputStyle, out, err));
        cmd.addSubcommand("use", new UseSubcommand(repository, outputStyle, out, err));
        cmd.addSubcommand("rm", new RmSubcommand(repository, outputStyle, out, err));
        return cmd;
    }

    @Override
    public Integer call() {
        return new ListSubcommand(repository, outputStyle, out, err).call();
    }

    @Command(name = "list", description = "List all profiles", mixinStandardHelpOptions = true)
    public static final class ListSubcommand implements Callable<Integer> {
        private final SqliteTokenRepository repository;
        private final OutputStyle outputStyle;
        private final PrintWriter out;
        private final PrintWriter err;

        @Option(names = {"--json"}, description = "Output profiles in JSON format")
        private boolean json;

        public ListSubcommand(
                @NotNull SqliteTokenRepository repository,
                @NotNull OutputStyle outputStyle,
                @NotNull PrintWriter out,
                @NotNull PrintWriter err
        ) {
            this.repository = repository;
            this.outputStyle = outputStyle;
            this.out = out;
            this.err = err;
        }

        @Override
        public Integer call() {
            try {
                List<Profile> profiles = repository.listProfiles();
                if (json) {
                    StringBuilder sb = new StringBuilder("[\n");
                    for (int i = 0; i < profiles.size(); i++) {
                        Profile p = profiles.get(i);
                        sb.append("  {\n");
                        sb.append("    \"name\": \"").append(escapeJson(p.name())).append("\",\n");
                        sb.append("    \"description\": ").append(p.description() == null ? "null" : "\"" + escapeJson(p.description()) + "\"").append(",\n");
                        sb.append("    \"active\": ").append(p.active()).append(",\n");
                        sb.append("    \"createdAt\": ").append(p.createdAt()).append("\n");
                        sb.append("  }");
                        if (i < profiles.size() - 1) {
                            sb.append(",");
                        }
                        sb.append("\n");
                    }
                    sb.append("]");
                    out.println(sb);
                } else {
                    for (Profile p : profiles) {
                        StringBuilder line = new StringBuilder();
                        if (p.active()) {
                            line.append(outputStyle.success("* " + p.name() + " (active)"));
                        } else {
                            line.append("  ").append(p.name());
                        }
                        if (p.description() != null && !p.description().isBlank()) {
                            line.append(" ").append(outputStyle.muted("- " + p.description()));
                        }
                        out.println(line);
                    }
                }
                return 0;
            } catch (Exception e) {
                err.println(outputStyle.error("Failed to list profiles: " + e.getMessage()));
                return 1;
            }
        }

        private String escapeJson(String s) {
            return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
        }
    }

    @Command(name = "create", description = "Create a new profile", mixinStandardHelpOptions = true)
    public static final class CreateSubcommand implements Callable<Integer> {
        private final SqliteTokenRepository repository;
        private final OutputStyle outputStyle;
        private final PrintWriter out;
        private final PrintWriter err;

        @Parameters(index = "0", description = "Name of the new profile")
        private String name;

        @Option(names = {"-d", "--desc", "--description"}, description = "Optional description")
        private String description;

        public CreateSubcommand(
                @NotNull SqliteTokenRepository repository,
                @NotNull OutputStyle outputStyle,
                @NotNull PrintWriter out,
                @NotNull PrintWriter err
        ) {
            this.repository = repository;
            this.outputStyle = outputStyle;
            this.out = out;
            this.err = err;
        }

        @Override
        public Integer call() {
            if (name == null || name.trim().isEmpty()) {
                err.println(outputStyle.error("Profile name cannot be empty"));
                return 2;
            }
            String profileName = name.trim();
            try {
                Optional<Profile> existing = repository.findProfile(profileName);
                if (existing.isPresent()) {
                    err.println(outputStyle.error("Profile already exists: " + profileName));
                    return 1;
                }
                Profile newProfile = new Profile(profileName, description, false, System.currentTimeMillis());
                repository.saveProfile(newProfile);
                out.println(outputStyle.success("Created profile: " + profileName));
                return 0;
            } catch (Exception e) {
                err.println(outputStyle.error("Failed to create profile: " + e.getMessage()));
                return 1;
            }
        }
    }

    @Command(name = "use", description = "Switch active profile", mixinStandardHelpOptions = true)
    public static final class UseSubcommand implements Callable<Integer> {
        private final SqliteTokenRepository repository;
        private final OutputStyle outputStyle;
        private final PrintWriter out;
        private final PrintWriter err;

        @Parameters(index = "0", description = "Name of the profile to activate")
        private String name;

        public UseSubcommand(
                @NotNull SqliteTokenRepository repository,
                @NotNull OutputStyle outputStyle,
                @NotNull PrintWriter out,
                @NotNull PrintWriter err
        ) {
            this.repository = repository;
            this.outputStyle = outputStyle;
            this.out = out;
            this.err = err;
        }

        @Override
        public Integer call() {
            if (name == null || name.trim().isEmpty()) {
                err.println(outputStyle.error("Profile name cannot be empty"));
                return 2;
            }
            String profileName = name.trim();
            try {
                boolean switched = repository.setActiveProfile(profileName);
                if (!switched) {
                    err.println(outputStyle.error("Profile not found: " + profileName));
                    return 1;
                }
                out.println(outputStyle.success("Switched to profile: " + profileName));
                return 0;
            } catch (Exception e) {
                err.println(outputStyle.error("Failed to switch profile: " + e.getMessage()));
                return 1;
            }
        }
    }

    @Command(name = "rm", description = "Delete a profile", mixinStandardHelpOptions = true)
    public static final class RmSubcommand implements Callable<Integer> {
        private final SqliteTokenRepository repository;
        private final OutputStyle outputStyle;
        private final PrintWriter out;
        private final PrintWriter err;

        @Parameters(index = "0", description = "Name of the profile to delete")
        private String name;

        public RmSubcommand(
                @NotNull SqliteTokenRepository repository,
                @NotNull OutputStyle outputStyle,
                @NotNull PrintWriter out,
                @NotNull PrintWriter err
        ) {
            this.repository = repository;
            this.outputStyle = outputStyle;
            this.out = out;
            this.err = err;
        }

        @Override
        public Integer call() {
            if (name == null || name.trim().isEmpty()) {
                err.println(outputStyle.error("Profile name cannot be empty"));
                return 2;
            }
            String profileName = name.trim();
            if ("default".equals(profileName)) {
                err.println(outputStyle.error("The 'default' profile cannot be deleted"));
                return 1;
            }
            try {
                boolean deleted = repository.deleteProfile(profileName);
                if (!deleted) {
                    err.println(outputStyle.error("Profile not found: " + profileName));
                    return 1;
                }
                out.println(outputStyle.success("Removed profile: " + profileName));
                return 0;
            } catch (Exception e) {
                err.println(outputStyle.error("Failed to delete profile: " + e.getMessage()));
                return 1;
            }
        }
    }
}

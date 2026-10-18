package io.github.lihuu.tokenkit.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import io.github.lihuu.tokenkit.core.storage.SqliteTokenRepository;
import org.jetbrains.annotations.NotNull;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "backup", description = "Backup and restore local encrypted database", mixinStandardHelpOptions = true)
public final class BackupCommand implements Callable<Integer> {
    private final SqliteTokenRepository repository;
    private final Path dbPath;
    private final OutputStyle outputStyle;
    private final PrintWriter out;
    private final PrintWriter err;

    public BackupCommand(
            @NotNull SqliteTokenRepository repository,
            @NotNull Path dbPath,
            @NotNull OutputStyle outputStyle,
            @NotNull PrintWriter out,
            @NotNull PrintWriter err
    ) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.dbPath = Objects.requireNonNull(dbPath, "dbPath must not be null");
        this.outputStyle = Objects.requireNonNull(outputStyle, "outputStyle must not be null");
        this.out = Objects.requireNonNull(out, "out must not be null");
        this.err = Objects.requireNonNull(err, "err must not be null");
    }

    public static CommandLine createCommandLine(
            @NotNull SqliteTokenRepository repository,
            @NotNull Path dbPath,
            @NotNull OutputStyle outputStyle,
            @NotNull PrintWriter out,
            @NotNull PrintWriter err
    ) {
        BackupCommand backupCmd = new BackupCommand(repository, dbPath, outputStyle, out, err);
        CommandLine cmd = new CommandLine(backupCmd);
        cmd.addSubcommand("create", new CreateSubcommand(repository, dbPath, outputStyle, out, err));
        cmd.addSubcommand("list", new ListSubcommand(dbPath, outputStyle, out, err));
        cmd.addSubcommand("restore", new RestoreSubcommand(repository, dbPath, outputStyle, out, err));
        return cmd;
    }

    @Override
    public Integer call() {
        return new CreateSubcommand(repository, dbPath, outputStyle, out, err).call();
    }

    @Command(name = "create", description = "Create a new local database backup", mixinStandardHelpOptions = true)
    public static final class CreateSubcommand implements Callable<Integer> {
        private final SqliteTokenRepository repository;
        private final Path dbPath;
        private final OutputStyle outputStyle;
        private final PrintWriter out;
        private final PrintWriter err;

        @Option(names = {"-o", "--out"}, description = "Destination backup file path")
        private String destination;

        public CreateSubcommand(
                @NotNull SqliteTokenRepository repository,
                @NotNull Path dbPath,
                @NotNull OutputStyle outputStyle,
                @NotNull PrintWriter out,
                @NotNull PrintWriter err
        ) {
            this.repository = repository;
            this.dbPath = dbPath;
            this.outputStyle = outputStyle;
            this.out = out;
            this.err = err;
        }

        @Override
        public Integer call() {
            try {
                Path targetPath;
                if (destination != null && !destination.trim().isEmpty()) {
                    targetPath = Path.of(destination.trim());
                } else {
                    Path backupDir = dbPath.getParent() != null
                            ? dbPath.getParent().resolve("backups")
                            : Path.of("backups");
                    if (!Files.exists(backupDir)) {
                        Files.createDirectories(backupDir);
                    }
                    String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
                    targetPath = backupDir.resolve("tokenkit-backup-" + timestamp + ".db");
                }

                repository.backupTo(targetPath);
                out.println(outputStyle.success("成功创建本地数据库备份: " + targetPath.toAbsolutePath()));
                return 0;
            } catch (Exception e) {
                err.println(outputStyle.error("Failed to create backup: " + e.getMessage()));
                return 1;
            }
        }
    }

    @Command(name = "list", description = "List local database backups", mixinStandardHelpOptions = true)
    public static final class ListSubcommand implements Callable<Integer> {
        private final Path dbPath;
        private final OutputStyle outputStyle;
        private final PrintWriter out;
        private final PrintWriter err;

        public ListSubcommand(
                @NotNull Path dbPath,
                @NotNull OutputStyle outputStyle,
                @NotNull PrintWriter out,
                @NotNull PrintWriter err
        ) {
            this.dbPath = dbPath;
            this.outputStyle = outputStyle;
            this.out = out;
            this.err = err;
        }

        @Override
        public Integer call() {
            Path backupDir = dbPath.getParent() != null
                    ? dbPath.getParent().resolve("backups")
                    : Path.of("backups");
            if (!Files.exists(backupDir)) {
                out.println(outputStyle.muted("暂无本地备份文件"));
                return 0;
            }

            try {
                List<Path> backups = new ArrayList<>();
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(backupDir, "*.db")) {
                    for (Path entry : stream) {
                        backups.add(entry);
                    }
                }

                if (backups.isEmpty()) {
                    out.println(outputStyle.muted("暂无本地备份文件"));
                    return 0;
                }

                backups.sort(Comparator.comparing(Path::getFileName).reversed());
                out.println(outputStyle.highlight("本地数据库备份列表 (" + backups.size() + "):"));
                for (Path b : backups) {
                    long size = Files.size(b);
                    out.println("  • " + outputStyle.highlight(b.getFileName().toString()) + outputStyle.muted(" (" + size + " bytes)"));
                }
                return 0;
            } catch (IOException e) {
                err.println(outputStyle.error("Failed to list backups: " + e.getMessage()));
                return 1;
            }
        }
    }

    @Command(name = "restore", description = "Restore database from a backup file", mixinStandardHelpOptions = true)
    public static final class RestoreSubcommand implements Callable<Integer> {
        private final SqliteTokenRepository repository;
        private final Path dbPath;
        private final OutputStyle outputStyle;
        private final PrintWriter out;
        private final PrintWriter err;

        @Parameters(index = "0", description = "Backup file path to restore from")
        private String backupFile;

        public RestoreSubcommand(
                @NotNull SqliteTokenRepository repository,
                @NotNull Path dbPath,
                @NotNull OutputStyle outputStyle,
                @NotNull PrintWriter out,
                @NotNull PrintWriter err
        ) {
            this.repository = repository;
            this.dbPath = dbPath;
            this.outputStyle = outputStyle;
            this.out = out;
            this.err = err;
        }

        @Override
        public Integer call() {
            if (backupFile == null || backupFile.trim().isEmpty()) {
                err.println(outputStyle.error("Please specify a backup file to restore"));
                return 2;
            }

            Path src = Path.of(backupFile.trim());
            if (!Files.exists(src)) {
                err.println(outputStyle.error("Backup file not found: " + src));
                return 1;
            }

            try {
                // Take a pre-restore backup if current database file exists
                if (Files.exists(dbPath)) {
                    Path backupDir = dbPath.getParent() != null
                            ? dbPath.getParent().resolve("backups")
                            : Path.of("backups");
                    if (!Files.exists(backupDir)) {
                        Files.createDirectories(backupDir);
                    }
                    String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
                    Path preRestorePath = backupDir.resolve("pre-restore-" + timestamp + ".db");
                    repository.backupTo(preRestorePath);
                }

                repository.close();
                Files.copy(src, dbPath, StandardCopyOption.REPLACE_EXISTING);
                out.println(outputStyle.success("成功还原数据库: " + src.toAbsolutePath()));
                return 0;
            } catch (Exception e) {
                err.println(outputStyle.error("Failed to restore database: " + e.getMessage()));
                return 1;
            }
        }
    }
}

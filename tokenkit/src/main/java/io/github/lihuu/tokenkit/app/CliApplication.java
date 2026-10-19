package io.github.lihuu.tokenkit.app;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import io.github.lihuu.tokenkit.cli.BackupCommand;
import io.github.lihuu.tokenkit.cli.CliCommand;
import io.github.lihuu.tokenkit.cli.EnvCommand;
import io.github.lihuu.tokenkit.cli.ExportCommand;
import io.github.lihuu.tokenkit.cli.GetCommand;
import io.github.lihuu.tokenkit.cli.ImportCommand;
import io.github.lihuu.tokenkit.cli.ListCommand;
import io.github.lihuu.tokenkit.cli.OutputStyle;
import io.github.lihuu.tokenkit.cli.ProfileCommand;
import io.github.lihuu.tokenkit.cli.RmCommand;
import io.github.lihuu.tokenkit.cli.RunCommand;
import io.github.lihuu.tokenkit.cli.SetCommand;
import io.github.lihuu.tokenkit.core.key.CompositeMasterKeyProvider;
import io.github.lihuu.tokenkit.core.key.MasterKeyProvider;
import io.github.lihuu.tokenkit.core.storage.DatabaseConfig;
import io.github.lihuu.tokenkit.core.storage.SqliteTokenRepository;
import org.jetbrains.annotations.NotNull;
import picocli.CommandLine;

public final class CliApplication {
    private CliApplication() {}

    public static void main(String[] args) {
        io.github.lihuu.clicommon.cli.CliRunner.pinUtf8Streams();
        PrintWriter out = new PrintWriter(System.out, true);
        PrintWriter err = new PrintWriter(System.err, true);
        Path dbPath = DatabaseConfig.defaultDatabasePath();
        Path configDir = dbPath.getParent() != null ? dbPath.getParent() : Path.of(".");
        configureSqliteNativeCache(configDir);
        MasterKeyProvider keyProvider = CompositeMasterKeyProvider.defaultProvider(configDir);
        int exitCode = run(args, dbPath, keyProvider, out, err, System.getenv(), System.console() != null);
        System.exit(exitCode);
    }

    private static void configureSqliteNativeCache(@NotNull Path configDir) {
        try {
            Path libDir = configDir.resolve("lib");
            String libName = org.sqlite.util.LibraryLoaderUtil.getNativeLibName();
            Path cachedLib = libDir.resolve(libName);
            if (!Files.exists(cachedLib)) {
                Files.createDirectories(libDir);
                String resourcePath = org.sqlite.util.LibraryLoaderUtil.getNativeLibResourcePath() + "/" + libName;
                try (java.io.InputStream in = org.sqlite.SQLiteJDBCLoader.class.getResourceAsStream(resourcePath)) {
                    if (in != null) {
                        Path tempTarget = libDir.resolve(libName + ".tmp");
                        Files.copy(in, tempTarget, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        Files.move(tempTarget, cachedLib, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            if (Files.exists(cachedLib)) {
                System.setProperty("org.sqlite.lib.path", libDir.toAbsolutePath().toString());
            }
            Path cacheDir = configDir.resolve(".cache");
            if (!Files.exists(cacheDir)) {
                Files.createDirectories(cacheDir);
            }
            System.setProperty("org.sqlite.tmpdir", cacheDir.toAbsolutePath().toString());
        } catch (Exception ignored) {
        }
    }

    public static int run(
            @NotNull String[] args,
            @NotNull Path dbPath,
            @NotNull MasterKeyProvider keyProvider,
            @NotNull PrintWriter out,
            @NotNull PrintWriter err
    ) {
        return run(args, dbPath, keyProvider, out, err, System.getenv(), false);
    }

    public static int run(
            @NotNull String[] args,
            @NotNull Path dbPath,
            @NotNull MasterKeyProvider keyProvider,
            @NotNull PrintWriter out,
            @NotNull PrintWriter err,
            @NotNull Map<String, String> env,
            boolean interactive
    ) {

        boolean noColorFlag = false;
        for (String arg : args) {
            if ("--no-color".equals(arg)) {
                noColorFlag = true;
                break;
            }
        }

        OutputStyle outputStyle = OutputStyle.of(env, interactive, noColorFlag);

        try (SqliteTokenRepository repository = new SqliteTokenRepository(dbPath, keyProvider)) {
            CliCommand rootCommand = new CliCommand(out);
            CommandLine cmd = new CommandLine(rootCommand);
            cmd.setOut(out);
            cmd.setErr(err);

            // Wire all subcommands
            cmd.addSubcommand("set", new SetCommand(repository, keyProvider, outputStyle, out, err));
            cmd.addSubcommand("get", new GetCommand(repository, keyProvider, outputStyle, out, err));
            cmd.addSubcommand("list", new ListCommand(repository, keyProvider, outputStyle, out, err));
            cmd.addSubcommand("rm", new RmCommand(repository, outputStyle, out, err));
            cmd.addSubcommand("env", new EnvCommand(repository, keyProvider, outputStyle, out, err));
            cmd.addSubcommand("run", new RunCommand(repository, keyProvider, outputStyle, out, err));
            cmd.addSubcommand("profile", ProfileCommand.createCommandLine(repository, outputStyle, out, err));
            cmd.addSubcommand("backup", BackupCommand.createCommandLine(repository, dbPath, outputStyle, out, err));
            cmd.addSubcommand("export", new ExportCommand(repository, keyProvider, outputStyle, out, err));
            cmd.addSubcommand("import", new ImportCommand(repository, keyProvider, outputStyle, out, err));

            return cmd.execute(args);
        } catch (Exception e) {
            err.println(outputStyle.error("Fatal initialization error: " + e.getMessage()));
            return 1;
        }
    }
}

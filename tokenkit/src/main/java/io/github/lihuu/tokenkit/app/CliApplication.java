package io.github.lihuu.tokenkit.app;

import java.io.PrintWriter;
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
        PrintWriter out = new PrintWriter(System.out, true);
        PrintWriter err = new PrintWriter(System.err, true);
        Path dbPath = DatabaseConfig.defaultDatabasePath();
        Path configDir = dbPath.getParent() != null ? dbPath.getParent() : Path.of(".");
        MasterKeyProvider keyProvider = CompositeMasterKeyProvider.defaultProvider(configDir);
        int exitCode = run(args, dbPath, keyProvider, out, err, System.getenv(), System.console() != null);
        System.exit(exitCode);
    }

    public static int run(
            String @NotNull [] args,
            @NotNull Path dbPath,
            @NotNull MasterKeyProvider keyProvider,
            @NotNull PrintWriter out,
            @NotNull PrintWriter err
    ) {
        return run(args, dbPath, keyProvider, out, err, System.getenv(), false);
    }

    public static int run(
            String @NotNull [] args,
            @NotNull Path dbPath,
            @NotNull MasterKeyProvider keyProvider,
            @NotNull PrintWriter out,
            @NotNull PrintWriter err,
            @NotNull Map<String, String> env,
            boolean interactive
    ) {
        Objects.requireNonNull(args, "args must not be null");
        Objects.requireNonNull(dbPath, "dbPath must not be null");
        Objects.requireNonNull(keyProvider, "keyProvider must not be null");
        Objects.requireNonNull(out, "out must not be null");
        Objects.requireNonNull(err, "err must not be null");
        Objects.requireNonNull(env, "env must not be null");

        boolean noColorFlag = false;
        for (String arg : args) {
            if ("--no-color".equals(arg)) {
                noColorFlag = true;
                break;
            }
        }

        OutputStyle outputStyle = OutputStyle.of(env, interactive, noColorFlag);

        try {
            SqliteTokenRepository repository = new SqliteTokenRepository(dbPath, keyProvider);
            repository.initializeSchema();

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

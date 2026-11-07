package io.github.lihuu.jsonkit.cli;

import io.github.lihuu.clicommon.cli.CliExecutionException;
import io.github.lihuu.clicommon.cli.CliExitCode;
import io.github.lihuu.clicommon.cli.CliUsageException;
import io.github.lihuu.clicommon.style.OutputStyle;
import io.github.lihuu.jsonkit.app.ApplicationInfo;
import io.github.lihuu.jsonkit.core.InvalidPathException;
import io.github.lihuu.jsonkit.core.JsonDocumentException;
import io.github.lihuu.jsonkit.core.JsonFormatter;
import io.github.lihuu.jsonkit.core.JsonHighlighter;
import io.github.lihuu.jsonkit.core.JsonQuery;
import io.github.lihuu.jsonkit.core.JsonTreeReader;
import io.github.lihuu.jsonkit.core.JsonValueWriter;
import io.github.lihuu.jsonkit.core.PathExpression;
import io.github.treetrail.jsonpath.JsonPathEvaluationException;
import io.github.treetrail.jsonpath.JsonPathLimitExceededException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(
        name = "jsonkit",
        mixinStandardHelpOptions = true,
        versionProvider = CliCommand.VersionProvider.class,
        description = "Format and query JSON. With no FILE and no --path, reads standard input when it is piped; "
                + "in a terminal it shows this help.")
public final class CliCommand implements Callable<Integer> {
    @Spec
    CommandSpec spec;

    @Parameters(index = "0", arity = "0..1", paramLabel = "FILE",
            description = "JSON file to read, or - for standard input")
    String file;

    @Option(names = {"-p", "--path"}, paramLabel = "PATH",
            description = "JSONPath query; shorthand forms are rooted at $")
    String path;

    @Option(names = "--no-color", description = "Disable ANSI color formatting")
    boolean noColor;

    @Option(names = "--debug", description = "Print a stack trace for unexpected failures")
    boolean debug;

    private final Path workingDirectory;
    private final Map<String, String> environment;
    private final boolean stdinInteractive;
    private final boolean stdoutInteractive;
    private final InputStream stdin;

    public CliCommand(
            @NotNull Path workingDirectory,
            @NotNull Map<String, String> environment,
            boolean stdinInteractive,
            boolean stdoutInteractive,
            @NotNull InputStream stdin
    ) {
        this.workingDirectory = workingDirectory;
        this.environment = environment;
        this.stdinInteractive = stdinInteractive;
        this.stdoutInteractive = stdoutInteractive;
        this.stdin = stdin;
    }

    @Override
    public @NotNull Integer call() {
        if (file == null && path == null && stdinInteractive) {
            spec.commandLine().usage(out(), outputStyle().isAnsi()
                    ? CommandLine.Help.Ansi.ON
                    : CommandLine.Help.Ansi.OFF);
            out().flush();
            return CliExitCode.OK;
        }
        if (file == null && path != null && stdinInteractive) {
            throw new CliUsageException("provide FILE or '-' to read standard input when using --path");
        }
        if (path == null) {
            formatDocument();
            return CliExitCode.OK;
        }
        PathExpression expression = parsePath(path);
        Object document = readDocument();
        try {
            JsonQuery.QueryResult result = JsonQuery.query(document, expression);
            writeResult(result);
        } catch (JsonPathLimitExceededException failure) {
            throw new CliExecutionException("query exceeded a resource limit: " + message(failure), failure);
        } catch (JsonPathEvaluationException failure) {
            throw new CliExecutionException(message(failure), failure);
        } catch (IOException failure) {
            throw new CliExecutionException("cannot write output: " + message(failure), failure);
        }
        return CliExitCode.OK;
    }

    private void formatDocument() {
        try {
            if (usesStdin()) {
                JsonFormatter.write(stdin, "<stdin>", out(), highlighter());
            } else {
                try (InputStream input = openFile()) {
                    JsonFormatter.write(input, file, out(), highlighter());
                }
            }
            out().flush();
        } catch (JsonDocumentException failure) {
            throw new CliExecutionException(failure.getMessage(), failure);
        } catch (IOException failure) {
            throw new CliExecutionException(sourceLabel() + ": " + message(failure), failure);
        }
    }

    private @Nullable Object readDocument() {
        try {
            if (usesStdin()) {
                return JsonTreeReader.read(stdin, "<stdin>");
            }
            try (InputStream input = openFile()) {
                return JsonTreeReader.read(input, file);
            }
        } catch (JsonDocumentException failure) {
            throw new CliExecutionException(failure.getMessage(), failure);
        } catch (IOException failure) {
            throw new CliExecutionException(sourceLabel() + ": " + message(failure), failure);
        }
    }

    private void writeResult(@NotNull JsonQuery.QueryResult result) throws IOException {
        if (result.singular()) {
            if (result.values().isEmpty()) {
                throw new CliExecutionException("path \"" + result.expression() + "\" matched no value");
            }
            JsonValueWriter.write(result.values().get(0), out(), highlighter());
        } else {
            JsonValueWriter.write(result.values(), out(), highlighter());
        }
        out().flush();
    }

    private @NotNull PathExpression parsePath(@NotNull String requested) {
        try {
            return PathExpression.parse(requested);
        } catch (InvalidPathException failure) {
            throw new CliUsageException(failure.getMessage());
        }
    }

    private @NotNull InputStream openFile() {
        try {
            return Files.newInputStream(workingDirectory.resolve(file));
        } catch (java.nio.file.InvalidPathException | IOException failure) {
            throw new CliExecutionException(sourceLabel() + ": " + message(failure), failure);
        }
    }

    private boolean usesStdin() {
        return file == null || "-".equals(file);
    }

    private @NotNull String sourceLabel() {
        return usesStdin() ? "<stdin>" : file;
    }

    private @NotNull PrintWriter out() {
        return spec.commandLine().getOut();
    }

    private @NotNull OutputStyle outputStyle() {
        return OutputStyle.of(environment, stdoutInteractive, noColor);
    }

    private @NotNull JsonHighlighter highlighter() {
        return outputStyle().isAnsi() ? JsonHighlighter.ansi() : JsonHighlighter.plain();
    }

    private static @NotNull String message(@NotNull Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    public static final class VersionProvider implements IVersionProvider {
        @Override
        public @NotNull String[] getVersion() {
            ApplicationInfo info = ApplicationInfo.load();
            return new String[]{info.name() + " " + info.version()};
        }
    }
}

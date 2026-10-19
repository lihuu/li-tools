package io.github.lihuu.clicommon.cli;

import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;

/**
 * Standard global command options for li-tools CLI tools.
 */
public class GlobalOptionsMixin {
    @Option(names = "--json", description = "Output results in structured JSON format", scope = ScopeType.INHERIT)
    public boolean json;

    @Option(names = "--quiet", description = "Suppress human-readable progress notes and explanations", scope = ScopeType.INHERIT)
    public boolean quiet;

    @Option(names = "--no-color", description = "Disable ANSI color formatting", scope = ScopeType.INHERIT)
    public boolean noColor;

    @Option(names = "--debug", description = "Output verbose debug information and stack traces on error", scope = ScopeType.INHERIT)
    public boolean debug;
}

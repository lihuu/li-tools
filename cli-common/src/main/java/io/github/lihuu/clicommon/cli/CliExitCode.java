package io.github.lihuu.clicommon.cli;

/**
 * Standard exit code contract for li-tools CLI matrix.
 */
public final class CliExitCode {
    private CliExitCode() {}

    /** Successful command execution. */
    public static final int OK = 0;

    /** Operational or runtime failure; error message emitted on stderr. */
    public static final int FAILURE = 1;

    /** Command-line usage error; syntax or parameter error. */
    public static final int USAGE = 2;
}

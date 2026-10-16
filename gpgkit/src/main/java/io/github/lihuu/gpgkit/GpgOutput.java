package io.github.lihuu.gpgkit;

/**
 * Picking the line of gpg's output that says what went wrong.
 *
 * <p>{@link Gpg.Result#reason()} takes the last line, which is right for most gpg failures and
 * wrong for a decryption that fails on damaged input: gpg prints
 * {@code gpg: invalid radix64 character 0B skipped} and then, after it has given up, one more
 * line of narration — {@code gpg: encrypted with 1 passphrase} — which reads as though the command
 * had described the file rather than rejected it. Reporting that line would send someone looking
 * for a passphrase problem that does not exist.
 */
final class GpgOutput {
    private GpgOutput() {
    }

    /** The last line of stderr that is an observation rather than narration. */
    static String reason(Gpg.Result result) {
        String[] lines = result.stderr().split("\n");
        for (int index = lines.length - 1; index >= 0; index--) {
            String line = lines[index].strip();
            if (!line.isEmpty() && !narration(line)) {
                return line;
            }
        }
        return result.reason();
    }

    private static boolean narration(String line) {
        return line.contains("encrypted data")
                || line.contains("encrypted with")
                || line.startsWith("gpg: keybox")
                || line.startsWith("gpg: trustdb")
                || line.startsWith("gpg: checking the trustdb");
    }
}

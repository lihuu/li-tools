package io.github.lihuu.mitmkit.cli;

import io.github.lihuu.clicommon.cli.CliExecutionException;
import io.github.lihuu.clicommon.cli.CliExitCode;
import io.github.lihuu.mitmkit.core.CaDefaults;
import io.github.lihuu.mitmkit.core.CertAuthority;
import org.jetbrains.annotations.NotNull;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.HexFormat;

@Command(
        name = "ca",
        description = "Generate or inspect the local root CA used for interception."
)
public final class CaCommand extends CliCommand.Action {
    @Option(names = "--force", description = "Rebuild the CA even if one already exists")
    boolean force;

    @Override
    int execute() throws Exception {
        Path caDir = CaDefaults.caDir(root.environment);
        Path caCertPath = caDir.resolve("ca.crt");
        Path caKeyPath = caDir.resolve("ca.key");

        if (Files.exists(caCertPath) && !force) {
            throw new CliExecutionException("CA already exists at " + caCertPath
                    + "; use --force to rebuild");
        }

        CertAuthority authority = new CertAuthority(caDir);
        try {
            X509Certificate ca = authority.ensureCa();
            if (root.globalOptions.json) {
                printJson(caCertPath, caKeyPath, ca);
            } else {
                out().println("CA generated: " + caCertPath);
                out().println("Private key:  " + caKeyPath);
                out().println("Subject:     " + ca.getSubjectX500Principal().getName());
            }
            out().flush();
            return CliExitCode.OK;
        } finally {
            authority.close();
        }
    }

    private void printJson(@NotNull Path caCertPath, @NotNull Path caKeyPath,
                           @NotNull X509Certificate ca) throws Exception {
        String fingerprint = fingerprint(ca);
        String notAfter = ca.getNotAfter().toInstant().toString();
        out().printf(
                "{\"caCert\":%s,\"caKey\":%s,\"subjectCn\":%s,\"sha256Fingerprint\":%s,\"notAfter\":%s}%n",
                quote(caCertPath.toString()), quote(caKeyPath.toString()),
                quote("mitmkit local CA"), quote(fingerprint), quote(notAfter));
    }

    private static @NotNull String fingerprint(@NotNull X509Certificate certificate) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        return HexFormat.of().formatHex(digest);
    }

    private static @NotNull String quote(@NotNull String value) {
        StringBuilder builder = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                default -> builder.append(character);
            }
        }
        return builder.append('"').toString();
    }
}

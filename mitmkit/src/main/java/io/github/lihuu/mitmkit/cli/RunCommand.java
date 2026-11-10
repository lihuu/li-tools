package io.github.lihuu.mitmkit.cli;

import io.github.lihuu.clicommon.cli.CliExecutionException;
import io.github.lihuu.clicommon.cli.CliExitCode;
import io.github.lihuu.mitmkit.core.CaDefaults;
import io.github.lihuu.mitmkit.core.CertAuthority;
import io.github.lihuu.mitmkit.core.FlowRecorder;
import io.github.lihuu.mitmkit.core.ProxyServer;
import org.jetbrains.annotations.NotNull;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

@Command(
        name = "run",
        description = "Start the MITM proxy and record flows until interrupted."
)
public final class RunCommand extends CliCommand.Action {
    @Option(names = "--host", description = "Listen address (default: 127.0.0.1)")
    String host = "127.0.0.1";

    @Option(names = "--port", description = "Listen port (default: 8080)")
    int port = 8080;

    @Option(names = "--insecure", description = "Skip upstream certificate verification")
    boolean insecure;

    @Option(names = "--body", description = "Record request and response bodies")
    boolean body;

    @Option(names = "--headers", description = "Record request and response headers")
    boolean headers;

    @Option(names = "--jsonl", description = "Emit newline-delimited JSON instead of human-readable lines")
    boolean jsonl;

    @Override
    int execute() throws Exception {
        Path caDir = CaDefaults.caDir(root.environment);
        CertAuthority authority = new CertAuthority(caDir);
        try {
            if (!CaDefaults.hasCa(caDir)) {
                err().println("No CA found; generating one at " + caDir);
                err().println("Install trust with: certkit trust install " + caDir.resolve("ca.crt"));
            }
            authority.ensureCa();

            FlowRecorder recorder = new FlowRecorder(spec.commandLine().getOut(), jsonl, body);

            ProxyServer proxy = new ProxyServer(host, port, authority, recorder, insecure, headers, body);
            proxy.start();
            spec.commandLine().getOut().println("mitmkit listening on " + host + ":" + proxy.port()
                    + " (Ctrl+C to stop)");
            spec.commandLine().getOut().flush();

            CountDownLatch shutdown = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                proxy.stop();
                shutdown.countDown();
            }));
            shutdown.await();
            return CliExitCode.OK;
        } catch (Exception failure) {
            throw new CliExecutionException("cannot start proxy: " + failure.getMessage(), failure);
        } finally {
            authority.close();
        }
    }
}

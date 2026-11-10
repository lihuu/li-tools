package io.github.lihuu.mitmkit.cli;

import io.github.lihuu.clicommon.cli.CliExecutionException;
import io.github.lihuu.clicommon.cli.CliExitCode;
import io.github.lihuu.clicommon.system.Platform;
import io.github.lihuu.mitmkit.core.CaDefaults;
import io.github.lihuu.mitmkit.core.CertAuthority;
import io.github.lihuu.mitmkit.core.FlowRecorder;
import io.github.lihuu.mitmkit.core.ProxyServer;
import io.github.lihuu.mitmkit.core.RewriteRule;
import io.github.lihuu.mitmkit.core.SystemProxy;
import org.jetbrains.annotations.NotNull;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;

@Command(
        name = "run",
        description = "Start the MITM proxy, rewrite traffic and record flows until interrupted."
)
public final class RunCommand extends CliCommand.Action {
    @Option(names = "--host", description = "Listen address (default: 127.0.0.1)")
    String host = "127.0.0.1";

    @Option(names = "--port", description = "Listen port (default: 8080)")
    int port = 8080;

    @Option(names = "--insecure", description = "Skip upstream certificate verification")
    boolean insecure;

    @Option(names = "--trust-local", description = "Trust loopback upstreams (127.0.0.1, localhost) without verification")
    boolean trustLocal;

    @Option(names = "--rewrite", description = "Rewrite rule: method:url:replaceUrl:reqHeader:resHeader:replaceBody. "
            + "Repeatable. Values use -> for replacements, e.g. --rewrite 'url:/v1/->/v2/'")
    List<String> rewriteFlags;

    @Option(names = "--system-proxy", description = "Set the OS system proxy while running and restore on exit")
    boolean systemProxy;

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
        SystemProxy[] proxyHolder = new SystemProxy[1];
        try {
            boolean caExisted = CaDefaults.hasCa(caDir);
            if (!caExisted) {
                err().println("Generating local CA at " + caDir);
            }
            authority.ensureCa();
            if (!caExisted) {
                err().println("Install trust once with: certkit trust install " + caDir.resolve("ca.crt"));
            }

            List<RewriteRule> rules = RewriteRule.parse(rewriteFlags == null ? List.of() : rewriteFlags);

            FlowRecorder recorder = new FlowRecorder(spec.commandLine().getOut(), jsonl, body);
            ProxyServer server = new ProxyServer(host, port, authority, recorder, insecure,
                    headers, body, trustLocal, rules);
            server.start();

            if (systemProxy) {
                SystemProxy proxy = new SystemProxy(server.port());
                proxy.apply(Platform.current());
                proxyHolder[0] = proxy;
                err().println("System proxy set; it will be restored on exit.");
            }

            spec.commandLine().getOut().println("mitmkit listening on " + host + ":" + server.port()
                    + " (Ctrl+C to stop)");
            spec.commandLine().getOut().println("Try: curl -x http://" + host + ":" + server.port()
                    + " --cacert " + caDir.resolve("ca.crt") + " https://example.com/");
            spec.commandLine().getOut().flush();

            CountDownLatch shutdown = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                server.stop();
                if (proxyHolder[0] != null) {
                    proxyHolder[0].close();
                }
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

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
import io.github.lihuu.mitmkit.core.TrustStore;
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

    @Option(names = "--install-trust", description = "Install the CA into the OS trust store (needs admin rights)")
    boolean installTrust;

    @Option(names = "--rewrite", description = "Advanced rewrite rule (semicolon-separated key:value pairs). "
            + "Repeatable. For simple whole-value replacement prefer --url/--header/--body.")
    List<String> rewriteFlags;

    @Option(names = "--match", description = "Only apply the simple rewrite flags to URLs containing this substring")
    String match;

    @Option(names = "--url", description = "Replace the request URL with this whole value")
    String url;

    @Option(names = "--header", description = "Append or replace a request header, e.g. --header 'X-Debug:1'")
    List<String> rewriteRequestHeaders;

    @Option(names = "--cookie", description = "Append or replace a cookie, e.g. --cookie 'session=abc'")
    List<String> cookies;

    @Option(names = "--body", description = "Replace the request body with this whole value")
    String requestBody;

    @Option(names = "--response-header", description = "Append or replace a response header")
    List<String> rewriteResponseHeaders;

    @Option(names = "--response-body", description = "Replace the response body with this whole value")
    String responseBody;

    @Option(names = "--system-proxy", description = "Set the OS system proxy while running and restore on exit")
    boolean systemProxy;

    @Option(names = "--record-body", description = "Record request and response bodies in the output")
    boolean body;

    @Option(names = "--record-headers", description = "Record request and response headers in the output")
    boolean headers;

    @Option(names = "--jsonl", description = "Emit newline-delimited JSON instead of human-readable lines")
    boolean jsonl;

    private static String first(List<String> values) {
        return values == null || values.isEmpty() ? null : values.get(0);
    }

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
            if (installTrust) {
                TrustStore trust = new TrustStore();
                TrustStore.TrustResult result = trust.install(caDir.resolve("ca.crt"));
                if (result.success()) {
                    err().println("Trust installed: " + result.message());
                } else {
                    err().println("Could not install trust: " + result.message());
                    if (result.suggestedCommand() != null) {
                        err().println("Run: " + result.suggestedCommand());
                    }
                }
            } else if (!caExisted) {
                err().println("Install trust once with: certkit trust install " + caDir.resolve("ca.crt"));
            }

            List<RewriteRule> rules = new java.util.ArrayList<>(
                    RewriteRule.parse(rewriteFlags == null ? List.of() : rewriteFlags));
            if (url != null || rewriteRequestHeaders != null || cookies != null || requestBody != null
                    || rewriteResponseHeaders != null || responseBody != null) {
                String requestHeader = first(rewriteRequestHeaders);
                String responseHeader = first(rewriteResponseHeaders);
                String cookie = first(cookies);
                rules.add(RewriteRule.simple("*", match, url, requestHeader, responseHeader,
                        cookie, requestBody, responseBody));
            }

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

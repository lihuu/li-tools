package io.github.lihuu.clicommon;

import java.security.Provider;
import java.security.Security;
import java.util.List;
import javax.net.ssl.SSLContext;
import org.jetbrains.annotations.NotNull;

/** JDK-only entry point, also compiled as a native executable during build verification. */
public final class NativeSecurityProbe {
    private NativeSecurityProbe() {}

    public static void main(String @NotNull [] args) throws Exception {
        for (String name : List.of("jdk.tls.disabledAlgorithms", "jdk.certpath.disabledAlgorithms",
                "jdk.jar.disabledAlgorithms", "jdk.tls.keyLimits", "crypto.policy", "keystore.type",
                "ssl.KeyManagerFactory.algorithm", "ssl.TrustManagerFactory.algorithm",
                "securerandom.source", "securerandom.strongAlgorithms")) {
            String value = Security.getProperty(name);
            if (value == null) {
                throw new AssertionError("Missing JDK security policy: " + name);
            }
            System.out.println(name + "=" + value);
        }
        List<String> protocols = List.of(SSLContext.getDefault().createSSLEngine().getEnabledProtocols());
        if (protocols.contains("TLSv1") || protocols.contains("TLSv1.1")) {
            throw new AssertionError("Legacy TLS enabled by default: " + protocols);
        }
        if (args.length == 1 && args[0].equals("pruned")) {
            List<String> allowed = List.of("SUN", "SunRsaSign", "SunEC", "SunJSSE", "SunJCE", "Apple");
            for (Provider provider : Security.getProviders()) {
                if (!allowed.contains(provider.getName())) {
                    throw new AssertionError("Unpruned provider: " + provider.getName());
                }
            }
        }
    }
}

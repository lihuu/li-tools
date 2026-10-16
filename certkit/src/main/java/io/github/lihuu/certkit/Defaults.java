package io.github.lihuu.certkit;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class Defaults {

    private Defaults() {}

    public static Path defaultCaDir() {
        String env = System.getenv("CERTKIT_CA_DIR");
        if (env != null && !env.isBlank()) {
            return Path.of(env);
        }
        return Path.of(System.getProperty("user.home"), ".certkit", "ca");
    }

    public static Path defaultCaCert() {
        return defaultCaDir().resolve("ca.crt");
    }

    public static Path defaultCaKey() {
        return defaultCaDir().resolve("ca.key");
    }

    public static boolean hasDefaultCa() {
        return java.nio.file.Files.exists(defaultCaCert()) && java.nio.file.Files.exists(defaultCaKey());
    }

    public static boolean isIpAddress(String str) {
        if (str == null || str.isBlank()) return false;
        // Check simple IPv4 format (e.g. 127.0.0.1)
        if (str.matches("^\\d{1,3}(\\.\\d{1,3}){3}$")) {
            return true;
        }
        // Check IPv6 (contains ':' and valid hex characters)
        if (str.contains(":")) {
            try {
                InetAddress addr = InetAddress.getByName(str);
                return addr instanceof java.net.Inet6Address;
            } catch (UnknownHostException ignored) {
                return false;
            }
        }
        return false;
    }

    public record HostSplit(List<String> domains, List<String> ips) {}

    public static HostSplit splitHosts(List<String> rawHosts) {
        if (rawHosts == null || rawHosts.isEmpty()) {
            return new HostSplit(List.of("localhost"), List.of("127.0.0.1"));
        }

        List<String> domains = new ArrayList<>();
        List<String> ips = new ArrayList<>();

        for (String raw : rawHosts) {
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) continue;
            if (isIpAddress(trimmed)) {
                ips.add(trimmed);
            } else {
                domains.add(trimmed);
            }
        }

        if (domains.isEmpty()) {
            domains.add("localhost");
        }
        if (ips.isEmpty()) {
            ips.add("127.0.0.1");
        }

        return new HostSplit(List.copyOf(domains), List.copyOf(ips));
    }
}

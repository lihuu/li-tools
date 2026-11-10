package io.github.lihuu.mitmkit.core;

import io.github.lihuu.clicommon.system.Platform;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Sets and restores the operating system HTTP proxy so every application on the
 * machine routes through the local mitmkit proxy while it runs.
 */
public final class SystemProxy implements AutoCloseable {
    private final int port;
    private final List<String> restoreCommands = new ArrayList<>();
    private boolean applied;

    public SystemProxy(int port) {
        this.port = port;
    }

    /** Applies the system proxy. Failures are reported but never fatal. */
    public void apply(@NotNull Platform.OS platform) {
        String proxy = "127.0.0.1:" + port;
        try {
            if (platform == Platform.OS.MACOS) {
                // networksetup needs the service name; Wi-Fi is the common case.
                for (String service : new String[]{"Wi-Fi", "Ethernet"}) {
                    run(List.of("networksetup", "-setwebproxy", service, "127.0.0.1", String.valueOf(port)));
                    run(List.of("networksetup", "-setsecurewebproxy", service, "127.0.0.1", String.valueOf(port)));
                    run(List.of("networksetup", "-setwebproxystate", service, "on"));
                    run(List.of("networksetup", "-setsecurewebproxystate", service, "on"));
                    restoreCommands.add("networksetup -setwebproxystate " + service + " off");
                    restoreCommands.add("networksetup -setsecurewebproxystate " + service + " off");
                }
            } else if (platform == Platform.OS.WINDOWS) {
                // The registry key is read by most Windows HTTP stacks.
                run(List.of("reg", "add", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings",
                        "/v", "ProxyEnable", "/t", "REG_DWORD", "/d", "1", "/f"));
                run(List.of("reg", "add", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings",
                        "/v", "ProxyServer", "/t", "REG_SZ", "/d", proxy, "/f"));
                restoreCommands.add("reg add HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings "
                        + "/v ProxyEnable /t REG_DWORD /d 0 /f");
            } else if (platform == Platform.OS.LINUX) {
                // gsettings covers GNOME; the environment covers the shell.
                run(List.of("gsettings", "set", "org.gnome.system.proxy", "mode", "'manual'"));
                run(List.of("gsettings", "set", "org.gnome.system.proxy.http", "host", "'127.0.0.1'"));
                run(List.of("gsettings", "set", "org.gnome.system.proxy.http", "port", String.valueOf(port)));
                run(List.of("gsettings", "set", "org.gnome.system.proxy.https", "host", "'127.0.0.1'"));
                run(List.of("gsettings", "set", "org.gnome.system.proxy.https", "port", String.valueOf(port)));
                restoreCommands.add("gsettings set org.gnome.system.proxy mode none");
            }
            applied = true;
        } catch (RuntimeException failure) {
            // Proxy setup must never prevent the tool from starting.
        }
    }

    @Override
    public void close() {
        if (!applied) return;
        for (String command : restoreCommands) {
            try {
                run(List.of("sh", "-c", command));
            } catch (RuntimeException ignored) {
            }
        }
        applied = false;
    }

    private static void run(@NotNull List<String> command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getOutputStream().close();
            process.getInputStream().readAllBytes();
            process.waitFor();
        } catch (IOException | InterruptedException failure) {
            throw new RuntimeException("command failed: " + String.join(" ", command), failure);
        }
    }
}

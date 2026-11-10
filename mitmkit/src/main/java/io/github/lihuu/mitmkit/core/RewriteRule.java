package io.github.lihuu.mitmkit.core;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A declarative request/response rewrite rule supplied on the command line.
 *
 * <p>Supported operations, applied in order: replace a URL substring, set a request
 * header, set a response header, or replace a body substring. Matching is by method
 * and an optional URL substring.
 */
public record RewriteRule(
        @NotNull String method,
        @Nullable String urlContains,
        @Nullable String replaceUrl,
        @Nullable String setRequestHeader,
        @Nullable String setResponseHeader,
        @Nullable String replaceBody,
        @Nullable String replaceBodyWith
) {
    public static @NotNull List<RewriteRule> parse(@NotNull List<String> flags) {
        List<RewriteRule> rules = new ArrayList<>();
        for (String flag : flags) {
            rules.add(parseOne(flag));
        }
        return rules;
    }

    private static @NotNull RewriteRule parseOne(@NotNull String flag) {
        String method = "*";
        String urlContains = null;
        String replaceUrl = null;
        String setRequestHeader = null;
        String setResponseHeader = null;
        String replaceBody = null;
        String replaceBodyWith = null;

        for (String token : flag.split(";")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) continue;
            int colon = trimmed.indexOf(':');
            if (colon <= 0) continue;
            String key = trimmed.substring(0, colon).trim();
            String value = trimmed.substring(colon + 1).trim();
            switch (key) {
                case "method" -> method = value;
                case "url" -> {
                    int arrow = value.indexOf("->");
                    if (arrow > 0) {
                        urlContains = value.substring(0, arrow).trim();
                        replaceUrl = value.substring(arrow + 2).trim();
                    } else {
                        urlContains = value;
                    }
                }
                case "replaceUrl" -> replaceUrl = value;
                case "reqHeader" -> setRequestHeader = value;
                case "resHeader" -> setResponseHeader = value;
                case "replaceBody" -> {
                    int arrow = value.indexOf("->");
                    if (arrow > 0) {
                        replaceBody = value.substring(0, arrow).trim();
                        replaceBodyWith = value.substring(arrow + 2).trim();
                    }
                }
                default -> {
                }
            }
        }
        return new RewriteRule(method, urlContains, replaceUrl, setRequestHeader,
                setResponseHeader, replaceBody, replaceBodyWith);
    }

    public boolean matches(@NotNull String requestMethod, @NotNull String url) {
        if (!"*".equals(method) && !method.equalsIgnoreCase(requestMethod)) return false;
        return urlContains == null || url.contains(urlContains);
    }

    public @NotNull String applyUrl(@NotNull String url) {
        if (replaceUrl == null) return url;
        return url.replace(urlContains, replaceUrl);
    }

    public @Nullable String requestHeaderValue() {
        return setRequestHeader;
    }

    public @Nullable String responseHeaderValue() {
        return setResponseHeader;
    }

    public @Nullable String applyBody(@NotNull String body) {
        if (replaceBody == null) return null;
        return body.replace(replaceBody, replaceBodyWith == null ? "" : replaceBodyWith);
    }
}

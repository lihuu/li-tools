package io.github.lihuu.mitmkit.core;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A declarative request/response rewrite rule supplied on the command line.
 *
 * <p>Operations: replace the whole URL, append-or-replace a request/response header,
 * replace a cookie, or replace the whole body. Matching is by method and an optional
 * URL substring. Rules are applied in declaration order.
 */
public record RewriteRule(
        @NotNull String method,
        @Nullable String urlContains,
        @Nullable String newUrl,
        @Nullable String requestHeader,
        @Nullable String responseHeader,
        @Nullable String cookie,
        @Nullable String newBody,
        @Nullable String newResponseBody
) {
    public static @NotNull List<RewriteRule> parse(@NotNull List<String> flags) {
        List<RewriteRule> rules = new ArrayList<>();
        for (String flag : flags) {
            rules.add(parseOne(flag));
        }
        return rules;
    }

    /**
     * Builds one rule from the simple CLI flags: a whole new body replaces the body,
     * a whole new URL replaces the URL, headers are appended or replaced.
     */
    public static @NotNull RewriteRule simple(
            @NotNull String method, @Nullable String urlContains, @Nullable String newUrl,
            @Nullable String requestHeader, @Nullable String responseHeader,
            @Nullable String cookie, @Nullable String newBody, @Nullable String newResponseBody) {
        return new RewriteRule(method, urlContains, newUrl, requestHeader, responseHeader,
                cookie, newBody, newResponseBody);
    }

    private static @NotNull RewriteRule parseOne(@NotNull String flag) {
        String method = "*";
        String urlContains = null;
        String newUrl = null;
        String requestHeader = null;
        String responseHeader = null;
        String cookie = null;
        String newBody = null;
        String newResponseBody = null;

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
                        newUrl = value.substring(arrow + 2).trim();
                    } else {
                        urlContains = value;
                    }
                }
                case "setUrl" -> newUrl = value;
                case "reqHeader" -> requestHeader = value;
                case "resHeader" -> responseHeader = value;
                case "cookie" -> cookie = value;
                case "body" -> newBody = value;
                case "resBody" -> newResponseBody = value;
                default -> {
                }
            }
        }
        return new RewriteRule(method, urlContains, newUrl, requestHeader, responseHeader,
                cookie, newBody, newResponseBody);
    }

    public boolean matches(@NotNull String requestMethod, @NotNull String url) {
        if (!"*".equals(method) && !method.equalsIgnoreCase(requestMethod)) return false;
        return urlContains == null || url.contains(urlContains);
    }

    public @NotNull String applyUrl(@NotNull String url) {
        if (newUrl == null) return url;
        if (urlContains == null) return newUrl;
        return url.replace(urlContains, newUrl);
    }

    public @Nullable String requestHeaderValue() {
        return requestHeader;
    }

    public @Nullable String responseHeaderValue() {
        return responseHeader;
    }

    public @Nullable String cookieValue() {
        return cookie;
    }

    public boolean replacesRequestBody() {
        return newBody != null;
    }

    public boolean replacesResponseBody() {
        return newResponseBody != null;
    }

    public byte[] requestBody(byte[] original) {
        return newBody == null ? original : newBody.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public byte[] responseBody(byte[] original) {
        return newResponseBody == null ? original : newResponseBody.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}

package io.github.lihuu.mitmkit.core;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Writes one record per intercepted flow to stdout, either as human-readable lines
 * or as newline-delimited JSON for scripts and agents.
 */
public final class FlowRecorder {
    private final PrintWriter out;
    private final boolean jsonLines;
    private final boolean includeBody;

    public FlowRecorder(@NotNull PrintWriter out, boolean jsonLines, boolean includeBody) {
        this.out = out;
        this.jsonLines = jsonLines;
        this.includeBody = includeBody;
    }

    public void record(@NotNull Flow flow) {
        if (jsonLines) {
            out.println(jsonLine(flow));
        } else {
            out.println(humanLine(flow));
        }
        out.flush();
    }

    private @NotNull String humanLine(@NotNull Flow flow) {
        if (flow.error() != null) {
            return flow.id() + " " + flow.method() + " " + flow.url() + " ERROR " + flow.error();
        }
        return flow.id() + " " + flow.method() + " " + flow.url() + " " + flow.status()
                + " " + flow.durationMs() + "ms " + flow.reqBytes() + "B/" + flow.resBytes() + "B";
    }

    private @NotNull String jsonLine(@NotNull Flow flow) {
        java.util.List<String> fields = new java.util.ArrayList<>();
        fields.add(field("id", String.valueOf(flow.id())));
        fields.add(field("ts", quote(flow.ts())));
        fields.add(field("method", quote(flow.method())));
        fields.add(field("url", quote(flow.url())));
        fields.add(field("status", flow.status() == null ? "null" : String.valueOf(flow.status())));
        fields.add(field("durationMs", String.valueOf(flow.durationMs())));
        fields.add(field("reqBytes", String.valueOf(flow.reqBytes())));
        fields.add(field("resBytes", String.valueOf(flow.resBytes())));
        if (flow.reqHeaders() != null) fields.add(field("reqHeaders", headers(flow.reqHeaders())));
        if (flow.resHeaders() != null) fields.add(field("resHeaders", headers(flow.resHeaders())));
        if (includeBody && flow.reqBody() != null) fields.add(field("reqBody", quote(flow.reqBody())));
        if (includeBody && flow.resBody() != null) fields.add(field("resBody", quote(flow.resBody())));
        if (flow.error() != null) fields.add(field("error", quote(flow.error())));
        return "{" + String.join(",", fields) + "}";
    }

    private static @NotNull String field(@NotNull String name, @NotNull String value) {
        return quote(name) + ":" + value;
    }

    private static @NotNull String headers(@NotNull Map<String, List<String>> headers) {
        StringBuilder builder = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (!first) builder.append(',');
            first = false;
            builder.append(quote(entry.getKey())).append(':');
            if (entry.getValue().size() == 1) {
                builder.append(quote(entry.getValue().get(0)));
            } else {
                builder.append('[');
                for (int index = 0; index < entry.getValue().size(); index++) {
                    if (index > 0) builder.append(',');
                    builder.append(quote(entry.getValue().get(index)));
                }
                builder.append(']');
            }
        }
        return builder.append('}').toString();
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
                case '\t' -> builder.append("\\t");
                default -> {
                    if (character < 0x20) {
                        builder.append(String.format("\\u%04x", (int) character));
                    } else {
                        builder.append(character);
                    }
                }
            }
        }
        return builder.append('"').toString();
    }

    public record Flow(
            long id,
            @NotNull String method,
            @NotNull String url,
            @Nullable Integer status,
            long durationMs,
            long reqBytes,
            long resBytes,
            @Nullable Map<String, List<String>> reqHeaders,
            @Nullable Map<String, List<String>> resHeaders,
            @Nullable String reqBody,
            @Nullable String resBody,
            @Nullable String error
    ) {
        public @NotNull String ts() {
            return java.time.Instant.now().toString();
        }
    }
}

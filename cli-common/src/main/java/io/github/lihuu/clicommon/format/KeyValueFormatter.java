package io.github.lihuu.clicommon.format;

import org.jetbrains.annotations.NotNull;
import java.util.List;
import java.util.Map;

/**
 * Key-value pair formatter with clean colon alignment.
 */
public final class KeyValueFormatter {
    private KeyValueFormatter() {}

    public static @NotNull String format(@NotNull List<Map.Entry<String, String>> entries, int indent) {
        if (entries.isEmpty()) {
            return "";
        }
        int maxKeyLen = 0;
        for (Map.Entry<String, String> entry : entries) {
            if (entry.getKey() != null && entry.getKey().length() > maxKeyLen) {
                maxKeyLen = entry.getKey().length();
            }
        }
        String indentSpace = " ".repeat(Math.max(0, indent));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            Map.Entry<String, String> entry = entries.get(i);
            String key = entry.getKey() != null ? entry.getKey() : "";
            String value = entry.getValue() != null ? entry.getValue() : "";
            int padding = maxKeyLen - key.length();
            sb.append(indentSpace)
              .append(key)
              .append(":")
              .append(" ".repeat(padding + 2))
              .append(value);
            if (i < entries.size() - 1) {
                sb.append("\n");
            }
        }
        return sb.toString();
    }
}

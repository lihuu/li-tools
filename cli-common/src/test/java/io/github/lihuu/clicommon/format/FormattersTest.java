package io.github.lihuu.clicommon.format;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FormattersTest {

    @Test
    void shouldFormatAlignedKeyValuePairs() {
        List<Map.Entry<String, String>> pairs = List.of(
                Map.entry("Subject CN", "internal.api"),
                Map.entry("SANs", "DNS:internal.api, IP:127.0.0.1"),
                Map.entry("Status", "OK")
        );

        String formatted = KeyValueFormatter.format(pairs, 2);
        assertTrue(formatted.contains("  Subject CN:  internal.api\n"));
        assertTrue(formatted.contains("  SANs:        DNS:internal.api, IP:127.0.0.1\n"));
        assertTrue(formatted.contains("  Status:      OK"));
    }

    @Test
    void shouldFormatTableWithAnsiSafeColumnAlignment() {
        List<TableFormatter.Column> columns = List.of(
                new TableFormatter.Column("NAME", 10),
                new TableFormatter.Column("STATUS", 8)
        );

        List<List<String>> rows = List.of(
                List.of("\u001b[36;1malpha\u001b[0m", "OK"),
                List.of("beta", "WARN")
        );

        String table = TableFormatter.format(columns, rows);
        assertTrue(table.contains("NAME"));
        assertTrue(table.contains("STATUS"));
        assertTrue(table.contains("─".repeat(20)));
        assertTrue(table.contains("alpha"));
        assertTrue(table.contains("beta"));
    }

    @Test
    void shouldMaskSensitiveStrings() {
        assertEquals("", TextMasker.mask(""));
        assertEquals("****", TextMasker.mask("abcd"));
        assertEquals("1••••6", TextMasker.mask("123456"));
        assertEquals("sk-pr••••a8B3", TextMasker.mask("sk-proj-1234567890a8B3"));
    }

    @Test
    void shouldFormatHexFingerprint() {
        String fp = "8A558CA321724D57C2DBA11DFE9D7F1F906B0A69";
        String formatted = TextMasker.fingerprint(fp);
        assertEquals("8A55 8CA3 2172 4D57 C2DB  A11D FE9D 7F1F 906B 0A69", formatted);
    }
}

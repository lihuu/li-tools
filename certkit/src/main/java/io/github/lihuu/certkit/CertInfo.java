package io.github.lihuu.certkit;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

public record CertInfo(
    String subjectCn,
    List<String> sans,
    String issuer,
    Instant notBefore,
    Instant notAfter,
    String sha256Fingerprint,
    String sigAlgName,
    String serialNumber
) {
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;
    private static final DateTimeFormatter HUMAN_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")
        .withZone(ZoneId.of("UTC"));

    public long daysRemaining() {
        return ChronoUnit.DAYS.between(Instant.now(), notAfter);
    }

    public boolean isExpired() {
        return Instant.now().isAfter(notAfter);
    }

    public boolean isExpiringSoon() {
        return !isExpired() && daysRemaining() <= 90;
    }

    public String statusLabel() {
        if (isExpired()) {
            return "EXPIRED (" + Math.abs(daysRemaining()) + " days ago)";
        }
        if (isExpiringSoon()) {
            return "WARN: expiring in " + daysRemaining() + " days";
        }
        return "OK (" + daysRemaining() + " days remaining)";
    }

    public String toHumanString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Certificate Overview:\n");
        sb.append(String.format("  Subject CN:   %s\n", subjectCn));
        sb.append(String.format("  SANs:         %s\n", String.join(", ", sans)));
        sb.append(String.format("  Status:       %s\n", statusLabel()));
        sb.append(String.format("  Valid From:   %s\n", HUMAN_DATE.format(notBefore)));
        sb.append(String.format("  Valid Until:  %s\n", HUMAN_DATE.format(notAfter)));
        sb.append(String.format("  Algorithm:    %s\n", sigAlgName));
        sb.append(String.format("  Issuer:       %s\n", issuer));
        sb.append(String.format("  SHA-256:      %s\n", sha256Fingerprint));
        sb.append(String.format("  Serial:       %s", serialNumber));
        return sb.toString();
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"subjectCn\": \"").append(escape(subjectCn)).append("\",\n");
        sb.append("  \"sans\": [");
        for (int i = 0; i < sans.size(); i++) {
            sb.append("\"").append(escape(sans.get(i))).append("\"");
            if (i < sans.size() - 1) sb.append(", ");
        }
        sb.append("],\n");
        sb.append("  \"issuer\": \"").append(escape(issuer)).append("\",\n");
        sb.append("  \"status\": \"").append(statusLabel()).append("\",\n");
        sb.append("  \"isExpired\": ").append(isExpired()).append(",\n");
        sb.append("  \"daysRemaining\": ").append(daysRemaining()).append(",\n");
        sb.append("  \"notBefore\": \"").append(ISO.format(notBefore)).append("\",\n");
        sb.append("  \"notAfter\": \"").append(ISO.format(notAfter)).append("\",\n");
        sb.append("  \"sha256Fingerprint\": \"").append(sha256Fingerprint).append("\",\n");
        sb.append("  \"algorithm\": \"").append(escape(sigAlgName)).append("\",\n");
        sb.append("  \"serialNumber\": \"").append(serialNumber).append("\"\n");
        sb.append("}");
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

package io.github.lihuu.certkit;

import org.jetbrains.annotations.NotNull;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

public record CertInfo(
    @NotNull String subjectCn,
    @NotNull List<String> sans,
    @NotNull String issuer,
    @NotNull Instant notBefore,
    @NotNull Instant notAfter,
    @NotNull String sha256Fingerprint,
    @NotNull String sigAlgName,
    @NotNull String serialNumber
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

    public @NotNull String statusLabel() {
        if (isExpired()) {
            return "EXPIRED (" + Math.abs(daysRemaining()) + " days ago)";
        }
        if (isExpiringSoon()) {
            return "WARN: expiring in " + daysRemaining() + " days";
        }
        return "OK (" + daysRemaining() + " days remaining)";
    }

    public @NotNull String toHumanString() {
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

    public @NotNull String toJson() {
        io.github.lihuu.clicommon.json.JsonWriter writer = new io.github.lihuu.clicommon.json.JsonWriter();
        writer.beginObject()
                .name("subjectCn").value(subjectCn)
                .name("sans").beginArray();
        for (String san : sans) {
            writer.value(san);
        }
        writer.endArray()
                .name("issuer").value(issuer)
                .name("status").value(statusLabel())
                .name("isExpired").value(isExpired())
                .name("daysRemaining").value(daysRemaining())
                .name("notBefore").value(ISO.format(notBefore))
                .name("notAfter").value(ISO.format(notAfter))
                .name("sha256Fingerprint").value(sha256Fingerprint)
                .name("algorithm").value(sigAlgName)
                .name("serialNumber").value(serialNumber)
                .endObject();
        return writer.toString();
    }
}

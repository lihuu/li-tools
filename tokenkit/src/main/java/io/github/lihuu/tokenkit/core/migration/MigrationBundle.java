package io.github.lihuu.tokenkit.core.migration;

import java.util.List;

public record MigrationBundle(
        int version,
        long exportedAt,
        List<ExportedToken> tokens
) {
    public record ExportedToken(
            String profile,
            String name,
            String kind,
            String value,
            String description,
            String tags,
            Long expiresAt,
            long createdAt,
            long updatedAt
    ) {}
}

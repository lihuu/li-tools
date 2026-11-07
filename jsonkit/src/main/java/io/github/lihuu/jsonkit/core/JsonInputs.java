package io.github.lihuu.jsonkit.core;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;

/** Shared strict token parser for viewing and querying. */
final class JsonInputs {
    private static final int MAX_DEPTH = 1_000;

    private static final JsonFactory FACTORY = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(MAX_DEPTH)
                    .build())
            .disable(StreamReadFeature.AUTO_CLOSE_SOURCE)
            .build();

    private JsonInputs() {}

    static @NotNull JsonParser open(@NotNull InputStream input) throws IOException {
        return FACTORY.createParser(input);
    }

    static @NotNull JsonDocumentException failure(@NotNull String source, @NotNull JsonProcessingException failure) {
        JsonLocation location = failure.getLocation();
        long line = location == null ? 0 : location.getLineNr();
        long column = location == null ? 0 : location.getColumnNr();
        String detail = failure.getOriginalMessage();
        if (detail == null || detail.isBlank()) {
            detail = failure.getClass().getSimpleName();
        }
        return new JsonDocumentException(source, line, column, detail, failure);
    }

    static @NotNull JsonDocumentException unexpected(
            @NotNull String source,
            @NotNull JsonParser parser,
            @NotNull String detail
    ) {
        return unexpected(source, parser, detail, null);
    }

    static @NotNull JsonDocumentException unexpected(
            @NotNull String source,
            @NotNull JsonParser parser,
            @NotNull String detail,
            @Nullable Throwable cause
    ) {
        JsonLocation location = parser.currentTokenLocation();
        long line = location == null ? 0 : location.getLineNr();
        long column = location == null ? 0 : location.getColumnNr();
        return new JsonDocumentException(source, line, column, detail, cause);
    }
}

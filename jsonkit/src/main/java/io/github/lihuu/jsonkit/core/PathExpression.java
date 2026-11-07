package io.github.lihuu.jsonkit.core;

import io.github.treetrail.jsonpath.EvaluationLimits;
import io.github.treetrail.jsonpath.JsonPath;
import io.github.treetrail.jsonpath.JsonPathSyntaxException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A validated JSONPath expression. Shorthand forms get the document root added at the CLI
 * boundary; everything after that uses one RFC 9535 engine.
 */
public final class PathExpression {
    private static final EvaluationLimits LIMITS = EvaluationLimits.DEFAULT
            .withMaxVisitedNodes(10_000_000L)
            .withMaxResultSize(1_000_000)
            .withMaxDepth(1_000);

    private final String expression;
    private final JsonPath path;

    private PathExpression(@NotNull String expression, @NotNull JsonPath path) {
        this.expression = expression;
        this.path = path;
    }

    public static @NotNull PathExpression parse(@Nullable String requested) throws InvalidPathException {
        String normalized = normalize(requested);
        try {
            return new PathExpression(normalized, JsonPath.compile(normalized).withLimits(LIMITS));
        } catch (JsonPathSyntaxException failure) {
            throw new InvalidPathException(requested, failure.reason(), failure.position(), failure);
        }
    }

    /** Adds the root node to the documented shorthand forms and leaves {@code $}-rooted paths alone. */
    public static @NotNull String normalize(@Nullable String requested) throws InvalidPathException {
        if (requested == null || requested.isBlank()) {
            throw new InvalidPathException("path must not be empty");
        }
        if (requested.startsWith("$")) {
            return requested;
        }
        if (requested.startsWith(".") || requested.startsWith("[")) {
            return "$" + requested;
        }
        return "$." + requested;
    }

    public @NotNull String expression() {
        return expression;
    }

    public boolean singular() {
        return path.isSingular();
    }

    public @NotNull List<Object> evaluate(@Nullable Object document) {
        return path.query(document).values();
    }
}

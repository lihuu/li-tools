package io.github.lihuu.jsonkit.core;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Runs a validated expression and reports whether its shape is a single value or an array. */
public final class JsonQuery {
    private JsonQuery() {}

    public record QueryResult(@NotNull String expression, boolean singular, @NotNull List<Object> values) {
        public QueryResult {
            values = Collections.unmodifiableList(new ArrayList<>(values));
        }
    }

    public static @NotNull QueryResult query(@Nullable Object document, @NotNull PathExpression expression) {
        return new QueryResult(expression.expression(), expression.singular(), expression.evaluate(document));
    }

    public static @NotNull QueryResult query(@Nullable Object document, @NotNull String requested)
            throws InvalidPathException {
        return query(document, PathExpression.parse(requested));
    }
}

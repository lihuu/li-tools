package io.github.lihuu.jsonkit.core;

import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;

/**
 * A JSON number that keeps its source literal, so formatting and query results never
 * rewrite big integers or high-precision decimals.
 */
public final class JsonNumber extends Number {
    private final String literal;
    private final BigDecimal decimal;

    public JsonNumber(@NotNull String literal) {
        this.literal = literal;
        this.decimal = new BigDecimal(literal);
    }

    public @NotNull String literal() {
        return literal;
    }

    public @NotNull BigDecimal decimalValue() {
        return decimal;
    }

    @Override
    public int intValue() {
        return decimal.intValue();
    }

    @Override
    public long longValue() {
        return decimal.longValue();
    }

    @Override
    public float floatValue() {
        return decimal.floatValue();
    }

    @Override
    public double doubleValue() {
        return decimal.doubleValue();
    }

    @Override
    public @NotNull String toString() {
        return literal;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof JsonNumber number && decimal.compareTo(number.decimal) == 0;
    }

    @Override
    public int hashCode() {
        return decimal.stripTrailingZeros().hashCode();
    }
}

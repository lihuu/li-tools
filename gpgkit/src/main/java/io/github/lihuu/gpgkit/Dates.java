package io.github.lihuu.gpgkit;

import java.time.Instant;
import java.time.ZoneId;

/**
 * Epoch seconds — the way gpg reports every date it prints — turned into something a person reads.
 */
final class Dates {
    private Dates() {
    }

    /**
     * The calendar date of an epoch second, in the machine's own zone.
     *
     * <p>Local and not UTC: "expires 2030-01-01" is a statement about the user's calendar, and a
     * date that differs depending on where the machine is would be a date nobody can plan against.
     */
    static String localDate(long epochSeconds) {
        return Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()).toLocalDate().toString();
    }

    /**
     * Whole days until an expiry, rounded up, so a key with twelve hours left reports 1 and not 0.
     *
     * <p>The number is read as "how long have I got", and the failure that costs something is
     * under-reporting it. Zero on the day it expires, zero for the partial day after it — callers
     * that need to distinguish an expired key ask {@link #expired}.
     */
    static long daysRemaining(long epochSeconds) {
        long secondsLeft = epochSeconds - Instant.now().getEpochSecond();
        return Math.floorDiv(secondsLeft + 86_399, 86_400);
    }

    static boolean expired(long epochSeconds) {
        return epochSeconds <= Instant.now().getEpochSecond();
    }
}

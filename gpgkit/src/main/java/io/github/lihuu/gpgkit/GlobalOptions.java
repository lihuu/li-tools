package io.github.lihuu.gpgkit;

/**
 * The three flags SPEC §2 declares global.
 *
 * <p>They are recognized wherever they appear — before the command or after it — and are removed
 * before a command sees its own arguments, so {@code gpgkit status --json} and
 * {@code gpgkit --json status} are the same invocation. A command therefore never has to parse
 * them, and never has to know that {@code --json} exists.
 */
record GlobalOptions(boolean json, boolean quiet, boolean help) {
}

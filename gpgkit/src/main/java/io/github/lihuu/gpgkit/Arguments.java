package io.github.lihuu.gpgkit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The option parser the artifact commands share.
 *
 * <p>Written once instead of four times because the interesting part is not "read the next
 * argument" — it is the decisions that are easy to get wrong in one copy and right in another:
 * an option that takes a value and is given none; an option repeated with two different values;
 * a file name that starts with a dash. A command that accepted {@code --key a --key b} by
 * silently keeping one of them would sign with a key its user did not choose.
 *
 * <p>{@code --name value} and {@code --name=value} are both accepted. The second form is what a
 * script reaches for, and refusing it would only produce a file named {@code =value} passed to
 * gpg. A lone {@code -} is a value, not an option — it is a file name here, never stdin.
 *
 * <p>The parser does not know which command it is parsing for: it reports "unknown option:
 * --foo" and leaves the command name, the usage text and the exit code to the caller, because
 * exit code 2 is part of each command's documented interface rather than of this helper.
 */
final class Arguments {
    private final List<String> positional;
    private final Map<String, String> values;
    private final Set<String> flags;

    private Arguments(List<String> positional, Map<String, String> values, Set<String> flags) {
        this.positional = positional;
        this.values = values;
        this.flags = flags;
    }

    /**
     * @param error a sentence for stderr, or null when the command line was well formed
     */
    record Result(Arguments arguments, String error) {
        boolean ok() {
            return error == null;
        }
    }

    static Result parse(List<String> args, Set<String> valueOptions, Set<String> booleanOptions) {
        List<String> positional = new ArrayList<>();
        Map<String, String> values = new LinkedHashMap<>();
        Set<String> flags = new LinkedHashSet<>();

        for (int index = 0; index < args.size(); index++) {
            String argument = args.get(index);
            if (!argument.startsWith("-") || argument.equals("-")) {
                positional.add(argument);
                continue;
            }

            String option = argument;
            String inlineValue = null;
            int equals = argument.indexOf('=');
            if (equals > 0) {
                option = argument.substring(0, equals);
                inlineValue = argument.substring(equals + 1);
            }

            if (booleanOptions.contains(option)) {
                if (inlineValue != null) {
                    return new Result(null, option + " takes no value");
                }
                if (!flags.add(option)) {
                    return new Result(null, "repeated option: " + option);
                }
                continue;
            }
            if (!valueOptions.contains(option)) {
                return new Result(null, "unknown option: " + option);
            }

            String value = inlineValue;
            if (value == null) {
                index++;
                if (index >= args.size()) {
                    return new Result(null, option + " needs a value");
                }
                value = args.get(index);
            }
            if (values.putIfAbsent(option, value) != null) {
                return new Result(null, "repeated option: " + option);
            }
        }
        return new Result(new Arguments(positional, values, flags), null);
    }

    /** Everything that was not an option, in the order it was written. */
    List<String> positional() {
        return positional;
    }

    boolean has(String option) {
        return flags.contains(option);
    }

    /** The value of an option that was not given, or null when it was. */
    String value(String option) {
        return values.get(option);
    }
}

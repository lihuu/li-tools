package io.github.lihuu.jsonkit.terminal;

import org.jetbrains.annotations.Nullable;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Optional;

/**
 * Reports the terminal state of standard input and standard output separately.
 *
 * <p>{@link System#console()} is only available when both streams are terminals, which
 * cannot distinguish a terminal reading piped JSON from a redirected result. The POSIX
 * {@code isatty} call answers each descriptor on its own; when the native call is not
 * available, the console check is the fallback.
 */
public final class Terminal {
    private static final @Nullable MethodHandle ISATTY = findIsatty();

    private Terminal() {}

    public static boolean stdinIsTerminal() {
        return isTerminal(0);
    }

    public static boolean stdoutIsTerminal() {
        return isTerminal(1);
    }

    private static boolean isTerminal(int descriptor) {
        if (ISATTY != null) {
            try {
                return ((int) ISATTY.invokeExact(descriptor)) != 0;
            } catch (Throwable ignored) {
                // A terminal probe must never prevent the CLI from running.
            }
        }
        return System.console() != null;
    }

    private static @Nullable MethodHandle findIsatty() {
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup lookup = linker.defaultLookup();
            Optional<MemorySegment> symbol = lookup.find("isatty");
            if (symbol.isEmpty()) {
                symbol = lookup.find("_isatty");
            }
            if (symbol.isEmpty()) {
                return null;
            }
            return linker.downcallHandle(
                    symbol.get(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }
}

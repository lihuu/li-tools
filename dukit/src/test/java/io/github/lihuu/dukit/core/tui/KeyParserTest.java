package io.github.lihuu.dukit.core.tui;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KeyParserTest {
    @Test
    void decodesArrowKeysInCsiAndSs3Forms() {
        assertEquals(Key.UP, parse(27, '[', 'A'));
        assertEquals(Key.DOWN, parse(27, '[', 'B'));
        assertEquals(Key.ENTER, parse(27, '[', 'C'));
        assertEquals(Key.BACK, parse(27, '[', 'D'));
        assertEquals(Key.UP, parse(27, 'O', 'A'));
        assertEquals(Key.DOWN, parse(27, 'O', 'B'));
        assertEquals(Key.HOME, parse(27, '[', 'H'));
        assertEquals(Key.END, parse(27, '[', 'F'));
    }

    @Test
    void decodesNumberedSequences() {
        assertEquals(Key.PAGE_UP, parse(27, '[', '5', '~'));
        assertEquals(Key.PAGE_DOWN, parse(27, '[', '6', '~'));
        assertEquals(Key.HOME, parse(27, '[', '1', '~'));
        assertEquals(Key.END, parse(27, '[', '4', '~'));
        assertEquals(Key.NONE, parse(27, '[', '9', '~'));
    }

    @Test
    void decodesPlainKeys() {
        assertEquals(Key.ENTER, parse('\r'));
        assertEquals(Key.ENTER, parse('\n'));
        assertEquals(Key.BACK, parse(127));
        assertEquals(Key.BACK, parse(8));
        assertEquals(Key.QUIT, parse('q'));
        assertEquals(Key.QUIT, parse(3));
        assertEquals(Key.SORT, parse('s'));
        assertEquals(Key.DOWN, parse('j'));
        assertEquals(Key.UP, parse('k'));
        assertEquals(Key.HOME, parse('g'));
        assertEquals(Key.END, parse('G'));
        assertEquals(Key.NONE, parse('z'));
    }

    @Test
    void endOfInputQuits() {
        assertEquals(Key.QUIT, parse(IntReader.END_OF_INPUT));
    }

    @Test
    void loneEscapeCancels() {
        assertEquals(Key.CANCEL, parse(27), "Esc is the universal back-out");
        assertEquals(Key.NONE, parse(27, 'x'), "an unknown sequence is not a key");
        assertEquals(Key.NONE, parse(27, '[', 0));
    }

    @Test
    void decodesTheDeleteGestures() {
        assertEquals(Key.DELETE, parse('d'));
        assertEquals(Key.CONFIRM, parse('y'));
        assertEquals(Key.CANCEL, parse('n'));
    }

    private static Key parse(int... bytes) {
        Deque<Integer> queue = new ArrayDeque<>();
        for (int value : bytes) {
            queue.add(value);
        }
        IntReader reader = timeoutMillis -> queue.isEmpty() ? IntReader.EXPIRED : queue.poll();
        return new KeyParser(reader).next();
    }
}

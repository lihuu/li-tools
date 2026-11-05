package io.github.lihuu.dukit.core.report;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HumanBytesTest {
    @Test
    void formatsBytesWithoutDecimals() {
        assertEquals("0 B", HumanBytes.format(0));
        assertEquals("1 B", HumanBytes.format(1));
        assertEquals("450 B", HumanBytes.format(450));
        assertEquals("1023 B", HumanBytes.format(1023));
    }

    @Test
    void usesOneDecimalBelowTenUnits() {
        assertEquals("1.0 KiB", HumanBytes.format(1024));
        assertEquals("1.5 KiB", HumanBytes.format(1536));
        assertEquals("9.8 KiB", HumanBytes.format(10_036));
        assertEquals("1.0 MiB", HumanBytes.format(1024 * 1024));
        assertEquals("3.0 GiB", HumanBytes.format(3L * 1024 * 1024 * 1024));
    }

    @Test
    void dropsDecimalsFromTenUnitsUp() {
        assertEquals("10 KiB", HumanBytes.format(10_240));
        assertEquals("12 MiB", HumanBytes.format(12L * 1024 * 1024));
        assertEquals("2.0 TiB", HumanBytes.format(2L * 1024 * 1024 * 1024 * 1024));
        assertEquals("1.0 PiB", HumanBytes.format(1024L * 1024 * 1024 * 1024 * 1024));
        assertEquals("8192 PiB", HumanBytes.format(Long.MAX_VALUE));
    }

    @Test
    void rejectsNegativeValues() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> HumanBytes.format(-1));
    }
}

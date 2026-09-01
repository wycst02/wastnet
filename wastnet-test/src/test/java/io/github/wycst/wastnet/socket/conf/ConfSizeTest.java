package io.github.wycst.wastnet.socket.conf;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * for Conf.getPropSize / parseSize (KB/MB/GB unit parsing).
 */
public class ConfSizeTest {

    private static final long KB = 1024L;
    private static final long MB = 1024L * 1024;
    private static final long GB = 1024L * 1024 * 1024;

    @Test
    public void absentReturnsDefault() {
        Properties p = new Properties();
        assertEquals(123L, Conf.getPropSize(p, "missing", 123L));
    }

    @Test
    public void bytesNoUnit() {
        Properties p = new Properties();
        p.setProperty("k", "2048");
        assertEquals(2048L, Conf.getPropSize(p, "k", 0));
    }

    @Test
    public void unitsUpperAndLower() {
        Properties p = new Properties();
        p.setProperty("kb", "1KB");
        p.setProperty("mb", "2MB");
        p.setProperty("gb", "3GB");
        p.setProperty("lower", "4kb");
        p.setProperty("spaced", " 5 MB ");
        p.setProperty("justB", "8B");
        assertEquals(1 * KB, Conf.getPropSize(p, "kb", 0));
        assertEquals(2 * MB, Conf.getPropSize(p, "mb", 0));
        assertEquals(3 * GB, Conf.getPropSize(p, "gb", 0));
        assertEquals(4 * KB, Conf.getPropSize(p, "lower", 0));
        assertEquals(5 * MB, Conf.getPropSize(p, "spaced", 0));
        assertEquals(8L, Conf.getPropSize(p, "justB", 0));
    }

    @Test
    public void invalidFallsBackToDefault() {
        Properties p = new Properties();
        p.setProperty("bad", "abc");
        p.setProperty("dec", "1.5MB");
        p.setProperty("xunit", "1XB");
        p.setProperty("overflow", "8589934592GB");
        // All rejected by parseSize -> IllegalArgumentException -> default.
        assertEquals(7L, Conf.getPropSize(p, "bad", 7L));
        assertEquals(7L, Conf.getPropSize(p, "dec", 7L));
        assertEquals(7L, Conf.getPropSize(p, "xunit", 7L));
        assertEquals(7L, Conf.getPropSize(p, "overflow", 7L));
    }

    @Test
    public void parseSizeUnitsDirect() {
        assertEquals(1024L, Conf.parseSize("1KB"));
        assertEquals(2 * MB, Conf.parseSize("2MB"));
        assertEquals(3 * GB, Conf.parseSize("3GB"));
        assertEquals(2048L, Conf.parseSize("2048"));
        assertEquals(8L, Conf.parseSize("8B"));
    }

    @Test
    public void parseSizeInvalidThrows() {
        assertThrows(IllegalArgumentException.class, () -> Conf.parseSize("abc"));
        assertThrows(IllegalArgumentException.class, () -> Conf.parseSize("1.5MB"));
        assertThrows(IllegalArgumentException.class, () -> Conf.parseSize("1XB"));
        assertThrows(IllegalArgumentException.class, () -> Conf.parseSize("8589934592GB"));
    }
}

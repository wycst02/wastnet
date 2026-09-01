package io.github.wycst.wastnet.util;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link Utils#encodeUriPath(String)}.
 */
public class UtilsTest {

    @Test
    public void testEncodeUriPathSafeAscii() {
        // Pure ASCII safe characters — returns original string (zero allocation)
        Assertions.assertSame("/hello/world", Utils.encodeUriPath("/hello/world"));
        Assertions.assertSame("index.html", Utils.encodeUriPath("index.html"));
        Assertions.assertSame("/api/users?id=1&name=test", Utils.encodeUriPath("/api/users?id=1&name=test"));
        Assertions.assertSame("-_.~", Utils.encodeUriPath("-_.~"));
        Assertions.assertSame("", Utils.encodeUriPath(""));
    }

    @Test
    public void testEncodeUriPathSpace() {
        Assertions.assertEquals("/hello%20world", Utils.encodeUriPath("/hello world"));
        Assertions.assertEquals("%20", Utils.encodeUriPath(" "));
    }

    @Test
    public void testEncodeUriPathSpecialChars() {
        // < > " should be percent-encoded
        Assertions.assertEquals("hello%3Cworld", Utils.encodeUriPath("hello<world"));
        Assertions.assertEquals("hello%3Eworld", Utils.encodeUriPath("hello>world"));
        Assertions.assertEquals("a%22b", Utils.encodeUriPath("a\"b"));
        // Percent sign itself
        Assertions.assertEquals("a%25b", Utils.encodeUriPath("a%b"));
    }

    @Test
    public void testEncodeUriPathNonAscii() {
        // Chinese characters, UTF-8 encoded: 你 = %E4%BD%A0, 好 = %E5%A5%BD
        Assertions.assertEquals("/%E4%BD%A0%E5%A5%BD", Utils.encodeUriPath("/你好"));
        Assertions.assertEquals("test/%E4%B8%AD%E6%96%87", Utils.encodeUriPath("test/中文"));
    }

    @Test
    public void testEncodeUriPathSurrogatePair() {
        // Emoji 😀 (U+1F600) — UTF-8: F0 9F 98 80
        Assertions.assertEquals("%F0%9F%98%80", Utils.encodeUriPath("\uD83D\uDE00"));
        Assertions.assertEquals("a%F0%9F%98%80b", Utils.encodeUriPath("a\uD83D\uDE00b"));
    }

    @Test
    public void testEncodeUriPathMixed() {
        // Safe prefix is preserved, then unsafe chars encoded
        Assertions.assertEquals("/safe/path/%E4%BD%A0%E5%A5%BD", Utils.encodeUriPath("/safe/path/你好"));
        Assertions.assertEquals("hello%20world%21", Utils.encodeUriPath("hello world!"));
    }

    @Test
    public void testEncodeUriPathPreservesStructuralChars() {
        // / ? = & # are safe — should not be encoded
        Assertions.assertEquals("/a/b/c", Utils.encodeUriPath("/a/b/c"));
        Assertions.assertEquals("?a=1&b=2#frag", Utils.encodeUriPath("?a=1&b=2#frag"));
    }

    @Test
    public void testEncodeUriPathAllUnsafe() {
        // Characters not in the safe set should be percent-encoded
        Assertions.assertEquals("%21%40%24%25%5E", Utils.encodeUriPath("!@$%^"));
    }

    // ==================== forCharsetName ====================

    @Test
    public void testForCharsetNameValid() {
        Assertions.assertSame(Utils.UTF_8, Utils.forCharsetName("UTF-8"));
        Assertions.assertEquals("ISO-8859-1", Utils.forCharsetName("ISO_8859_1").name());
    }

    @Test
    public void testForCharsetNameInvalid() {
        try {
            Utils.forCharsetName("NO_SUCH_CHARSET");
            Assertions.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            Assertions.assertTrue(expected.getMessage().contains("Unsupported charset"));
        }
    }

    @Test
    public void testForCharsetNameWithDefault() {
        Charset def = Charset.forName("UTF-8");
        Assertions.assertSame(def, Utils.forCharsetName("UTF-8", def));
        // invalid name falls back to default
        Assertions.assertSame(def, Utils.forCharsetName("BAD", def));
    }

    // ==================== shutdownExecutorService ====================

    @Test
    public void testShutdownExecutorServiceNormal() throws Exception {
        ExecutorService exec = mock(ExecutorService.class);
        when(exec.awaitTermination(eq(5000L), eq(TimeUnit.MILLISECONDS))).thenReturn(true);
        Utils.shutdownExecutorService(exec);
        verify(exec).shutdown();
        verify(exec, never()).shutdownNow();
    }

    @Test
    public void testShutdownExecutorServiceTimeoutTriggersShutdownNow() throws Exception {
        ExecutorService exec = mock(ExecutorService.class);
        when(exec.awaitTermination(eq(5000L), eq(TimeUnit.MILLISECONDS))).thenReturn(false);
        Utils.shutdownExecutorService(exec);
        verify(exec).shutdown();
        verify(exec).shutdownNow();
    }

    @Test
    public void testShutdownExecutorServiceAwaitThrowsTriggersShutdownNow() throws Exception {
        ExecutorService exec = mock(ExecutorService.class);
        when(exec.awaitTermination(eq(5000L), eq(TimeUnit.MILLISECONDS))).thenThrow(new RuntimeException("boom"));
        Utils.shutdownExecutorService(exec);
        verify(exec).shutdown();
        verify(exec).shutdownNow();
    }

    // ==================== toHexString16 / hex / id ====================

    @Test
    public void testToHexString16() {
        Assertions.assertEquals("0000000000000000", Utils.toHexString16(0L));
        Assertions.assertEquals("000000000000000A", Utils.toHexString16(10L));
        Assertions.assertEquals("FFFFFFFFFFFFFFFF", Utils.toHexString16(-1L));
        Assertions.assertEquals("000000000000000F", Utils.toHexString16(0x0fL));
    }

    @Test
    public void testHexAndId() {
        long before = Utils.id();
        long a = Utils.id();
        long b = Utils.id();
        Assertions.assertEquals(a + 1, b);
        Assertions.assertTrue(before < b);
        String h = Utils.hex();
        Assertions.assertEquals(16, h.length());
        Assertions.assertTrue(h.matches("[0-9A-F]{16}"));
    }

    // ==================== fnv64 ====================

    @Test
    public void testFnv64() {
        long r1 = Utils.fnv64(0L, 'a');
        Assertions.assertEquals(('a') * 0x100000001b3L, r1);
        long r2 = Utils.fnv64(r1, 'b');
        Assertions.assertEquals((r1 ^ 'b') * 0x100000001b3L, r2);
    }

    // ==================== printHexString ====================

    @Test
    public void testPrintHexString() {
        byte[] data = {0x00, 0x0a, (byte) 0xff, (byte) 0x1f};
        Assertions.assertEquals("000AFF1F", Utils.printHexString(data, (char) 0));
        Assertions.assertEquals("00:0A:FF:1F:", Utils.printHexString(data, ':'));
        Assertions.assertEquals("05", Utils.printHexString(new byte[]{5}, (char) 0));
    }

    // ==================== hexNibble ====================

    @Test
    public void testHexNibble() {
        Assertions.assertEquals(0, Utils.hexNibble('0'));
        Assertions.assertEquals(9, Utils.hexNibble('9'));
        Assertions.assertEquals(10, Utils.hexNibble('A'));
        Assertions.assertEquals(15, Utils.hexNibble('F'));
        Assertions.assertEquals(10, Utils.hexNibble('a'));
        Assertions.assertEquals(15, Utils.hexNibble('f'));
        // invalid -> -1
        Assertions.assertEquals(-1, Utils.hexNibble('G'));
        Assertions.assertEquals(-1, Utils.hexNibble(' '));
        // out of bounds -> -1 (table length is 103)
        Assertions.assertEquals(-1, Utils.hexNibble(200));
    }

    // ==================== intToHexBytes ====================

    @Test
    public void testIntToHexBytes() {
        byte[] buf = new byte[16];
        Assertions.assertEquals(1, Utils.intToHexBytes(0, buf, 0));
        Assertions.assertEquals('0', buf[0]);
        Assertions.assertEquals(1, Utils.intToHexBytes(0xf, buf, 0));
        Assertions.assertEquals('f', buf[0]);
        Assertions.assertEquals(2, Utils.intToHexBytes(0x1a, buf, 0));
        Assertions.assertEquals('1', buf[0]);
        Assertions.assertEquals('a', buf[1]);
        Assertions.assertEquals(8, Utils.intToHexBytes(-1, buf, 0));
        Assertions.assertEquals('f', buf[7]);
        Assertions.assertEquals(2, Utils.intToHexBytes(0x2b, buf, 2));
        Assertions.assertEquals('2', buf[2]);
        Assertions.assertEquals('b', buf[3]);
    }

    // ==================== escapeSpecialString ====================

    @Test
    public void testEscapeSpecialString() {
        Assertions.assertEquals("", Utils.escapeSpecialString(null));
        Assertions.assertEquals("abc", Utils.escapeSpecialString("abc"));
        Assertions.assertEquals("\\\"", Utils.escapeSpecialString("\""));
        Assertions.assertEquals("\\\\", Utils.escapeSpecialString("\\"));
        Assertions.assertEquals("a\\nb", Utils.escapeSpecialString("a\nb"));
        Assertions.assertEquals("a\\tb", Utils.escapeSpecialString("a\tb"));
        Assertions.assertEquals("a\\rb", Utils.escapeSpecialString("a\rb"));
        Assertions.assertEquals("a\\bb", Utils.escapeSpecialString("a\bb"));
        Assertions.assertEquals("a\\fb", Utils.escapeSpecialString("a\fb"));
        // control char below ' '
        Assertions.assertEquals("a\\u0001b", Utils.escapeSpecialString("a\u0001b"));
    }

    // ==================== hexDump ====================

    @Test
    public void testHexDumpNullAndEmpty() {
        // : data == null and len <= 0 both return ""
        Assertions.assertEquals("", Utils.hexDump(null, 0, 8));
        Assertions.assertEquals("", Utils.hexDump(new byte[8], 0, 0));
        Assertions.assertEquals("", Utils.hexDump(new byte[8], 0, -1));
    }

    @Test
    public void testHexDumpExactOneLine() {
        // 16 bytes -> exactly one data line, no trailing padding, all printable-ish (0x00 -> '.')
        byte[] data = new byte[16]; // all zeros
        String dump = Utils.hexDump(data, 0, 16);
        Assertions.assertTrue(dump.startsWith("---------"));
        Assertions.assertTrue(dump.trim().endsWith("+"));
        Assertions.assertTrue(dump.contains("00 00 00 00 00 00 00 00  00 00 00 00 00 00 00 00"));
        // : non-printable 0x00 rendered as '.' (16 of them)
        Assertions.assertTrue(dump.contains("| ................ |"));
    }

    @Test
    public void testHexDumpMultipleLinesWithPadding() {
        // 32 bytes -> two full lines (exercises outer loop multi-iteration)
        byte[] data = new byte[32];
        for (int i = 0; i < 32; i++) data[i] = (byte) i;
        String dump = Utils.hexDump(data, 0, 32);
        // : half-gap space appears at the 8th byte of each line
        Assertions.assertTrue(dump.contains("00 01 02 03 04 05 06 07  08 09 0A 0B 0C 0D 0E 0F"));
        Assertions.assertTrue(dump.contains("10 11 12 13 14 15 16 17  18 19 1A 1B 1C 1D 1E 1F"));
        // : printable ASCII for 0x20-0x7E, '.' for the rest (here all printable)
        Assertions.assertTrue(dump.contains("| ................ |"));
    }

    @Test
    public void testHexDumpPartialLastLine() {
        // 20 bytes -> first line full (16), second line has 4 real bytes + 12 padding spaces.
        byte[] data = new byte[20];
        // first 16 zeros, then 'A'(0x41), 'B'(0x42), LF(0x0A), DEL(0x7F)
        data[16] = 0x41;
        data[17] = 0x42;
        data[18] = 0x0A;
        data[19] = 0x7F;
        String dump = Utils.hexDump(data, 0, 20);
        String[] lines = dump.split("\n");
        Assertions.assertTrue(lines[1].contains("00 00 00 00 00 00 00 00  00 00 00 00 00 00 00 00"));
        //  false branch: second line has only 4 real bytes then blank padding
        Assertions.assertTrue(lines[2].contains("41 42 0A 7F"));
        // : 'A','B' printable, 0x0A & 0x7F non-printable -> rendered as '.'
        Assertions.assertTrue(lines[2].contains("AB.."));
    }

    @Test
    public void testHexDumpOffset() {
        // off > 0 must skip the leading bytes ( off+i+j)
        byte[] data = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C};
        // dump 8 bytes starting at offset 4 -> should show 05..0C, not 01..08
        String dump = Utils.hexDump(data, 4, 8);
        Assertions.assertTrue(dump.contains("05 06 07 08 09 0A 0B 0C"));
        Assertions.assertFalse(dump.contains("01 02 03 04"));
    }

    @Test
    public void testHexDumpSingleNonPrintableOnly() {
        // single byte 0x7F ->  '.' branch on a one-byte (padded) line
        String dump = Utils.hexDump(new byte[]{(byte) 0x7F}, 0, 1);
        String[] lines = dump.split("\n");
        Assertions.assertTrue(lines[1].contains("7F"));
        Assertions.assertTrue(lines[1].contains("| .                |"));
    }
}

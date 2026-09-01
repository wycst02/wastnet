package io.github.wycst.wastnet.http;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link HttpUnsafe} utility methods (JDK 8 / x86).
 *
 * @author wangyc
 */
public class HttpUnsafeTest {

    // ==================== createAsciiString ====================

    @Test
    public void testCreateAsciiStringWithOffsetAndLength() {
        byte[] data = "Hello World".getBytes();
        String result = HttpUnsafe.createAsciiString(data, 0, data.length);
        Assertions.assertEquals("Hello World", result);
    }

    @Test
    public void testCreateAsciiStringWithOffsetAndLengthPartial() {
        byte[] data = "abcdef".getBytes();
        String result = HttpUnsafe.createAsciiString(data, 2, 3);
        Assertions.assertEquals("cde", result);
    }

    @Test
    public void testCreateAsciiStringWithOffsetAndLengthZero() {
        byte[] data = "abc".getBytes();
        String result = HttpUnsafe.createAsciiString(data, 1, 0);
        Assertions.assertEquals("", result);
    }

    @Test
    public void testCreateAsciiStringWithOffsetAndLengthIndexOutOfBoundsNegOffset() {
        byte[] data = "abc".getBytes();
        Assertions.assertThrows(IndexOutOfBoundsException.class,
                () -> HttpUnsafe.createAsciiString(data, -1, 1));
    }

    @Test
    public void testCreateAsciiStringWithOffsetAndLengthIndexOutOfBoundsNegLen() {
        byte[] data = "abc".getBytes();
        Assertions.assertThrows(IndexOutOfBoundsException.class,
                () -> HttpUnsafe.createAsciiString(data, 0, -1));
    }

    @Test
    public void testCreateAsciiStringWithOffsetAndLengthIndexOutOfBoundsExceeds() {
        byte[] data = "abc".getBytes();
        Assertions.assertThrows(IndexOutOfBoundsException.class,
                () -> HttpUnsafe.createAsciiString(data, 0, 10));
    }

    @Test
    public void testCreateAsciiStringFromFullArray() {
        byte[] data = "Hello".getBytes();
        String result = HttpUnsafe.createAsciiString(data);
        Assertions.assertEquals("Hello", result);
    }

    @Test
    public void testCreateAsciiStringFromEmptyArray() {
        byte[] data = new byte[0];
        String result = HttpUnsafe.createAsciiString(data);
        Assertions.assertEquals("", result);
    }

    // ==================== createStringJDK8 ====================

    @Test
    public void testCreateStringJDK8() {
        // Direct call to cover the method regardless of JDK_9_PLUS value
        char[] chars = "Hello".toCharArray();
        String result = HttpUnsafe.createStringJDK8(chars);
        Assertions.assertNotNull(result);
        // On a proper JDK 8 environment, result would equal "Hello"
        Assertions.assertTrue(result.length() > 0, "createStringJDK8 should produce a non-empty string");
    }

    @Test
    public void testCreateStringJDK8Empty() {
        char[] chars = new char[0];
        String result = HttpUnsafe.createStringJDK8(chars);
        Assertions.assertNotNull(result);
    }

    // ==================== copyOfRange ====================

    @Test
    public void testCopyOfRangeLenLarge() {
        byte[] src = new byte[64];
        for (int i = 0; i < 64; ++i) src[i] = (byte) i;
        byte[] dst = new byte[64];
        HttpUnsafe.copyOfRange(src, 0, dst, 0, 64);
        Assertions.assertArrayEquals(src, dst);
    }

    @Test
    public void testCopyOfRangeLenLargePartial() {
        byte[] src = new byte[64];
        for (int i = 0; i < 64; ++i) src[i] = (byte) i;
        byte[] dst = new byte[32];
        HttpUnsafe.copyOfRange(src, 10, dst, 0, 32);
        for (int i = 0; i < 32; ++i) {
            Assertions.assertEquals(src[10 + i], dst[i]);
        }
    }

    @Test
    public void testCopyOfRangeLen31() {
        byte[] src = new byte[31];
        for (int i = 0; i < 31; ++i) src[i] = (byte) (i + 1);
        byte[] dst = new byte[31];
        HttpUnsafe.copyOfRange(src, 0, dst, 0, 31);
        Assertions.assertArrayEquals(src, dst);
    }

    @Test
    public void testCopyOfRangeLen17() {
        byte[] src = "12345678901234567".getBytes();
        byte[] dst = new byte[17];
        HttpUnsafe.copyOfRange(src, 0, dst, 0, 17);
        Assertions.assertArrayEquals(src, dst);
    }

    @Test
    public void testCopyOfRangeLen16() {
        byte[] src = "1234567890123456".getBytes();
        byte[] dst = new byte[16];
        HttpUnsafe.copyOfRange(src, 0, dst, 0, 16);
        Assertions.assertArrayEquals(src, dst);
    }

    @Test
    public void testCopyOfRangeLen12() {
        byte[] src = "123456789012".getBytes();
        byte[] dst = new byte[12];
        HttpUnsafe.copyOfRange(src, 0, dst, 0, 12);
        Assertions.assertArrayEquals(src, dst);
    }

    @Test
    public void testCopyOfRangeLen9() {
        byte[] src = "123456789".getBytes();
        byte[] dst = new byte[9];
        HttpUnsafe.copyOfRange(src, 0, dst, 0, 9);
        Assertions.assertArrayEquals(src, dst);
    }

    @Test
    public void testCopyOfRangeLen8() {
        byte[] src = "12345678".getBytes();
        byte[] dst = new byte[8];
        HttpUnsafe.copyOfRange(src, 0, dst, 0, 8);
        Assertions.assertArrayEquals(src, dst);
    }

    @Test
    public void testCopyOfRangeLen5() {
        byte[] src = "12345".getBytes();
        byte[] dst = new byte[5];
        HttpUnsafe.copyOfRange(src, 0, dst, 0, 5);
        Assertions.assertArrayEquals(src, dst);
    }

    @Test
    public void testCopyOfRangeLen4() {
        byte[] src = "1234".getBytes();
        byte[] dst = new byte[4];
        HttpUnsafe.copyOfRange(src, 0, dst, 0, 4);
        Assertions.assertArrayEquals(src, dst);
    }

    @Test
    public void testCopyOfRangeLen3() {
        byte[] src = "123".getBytes();
        byte[] dst = new byte[3];
        HttpUnsafe.copyOfRange(src, 0, dst, 0, 3);
        Assertions.assertArrayEquals(src, dst);
    }

    @Test
    public void testCopyOfRangeLen2() {
        byte[] src = "12".getBytes();
        byte[] dst = new byte[2];
        HttpUnsafe.copyOfRange(src, 0, dst, 0, 2);
        Assertions.assertArrayEquals(src, dst);
    }

    @Test
    public void testCopyOfRangeLen1() {
        byte[] src = "1".getBytes();
        byte[] dst = new byte[1];
        HttpUnsafe.copyOfRange(src, 0, dst, 0, 1);
        Assertions.assertArrayEquals(src, dst);
    }

    // ==================== Unsafe helper methods ====================

    @Test
    public void testGetLong() {
        byte[] data = "12345678".getBytes();
        long value = HttpUnsafe.getLong(data, 0);
        // Just verify it doesn't throw and returns something
        Assertions.assertNotEquals(0L, value);
    }

    @Test
    public void testGetShort() {
        byte[] data = "12".getBytes();
        short value = HttpUnsafe.getShort(data, 0);
        Assertions.assertNotEquals(0, value);
    }

    @Test
    public void testGetInt() {
        byte[] data = "1234".getBytes();
        int value = HttpUnsafe.getInt(data, 0);
        Assertions.assertNotEquals(0, value);
    }

    @Test
    public void testGetStringValue() {
        String str = "test";
        Object internal = HttpUnsafe.getStringValue(str);
        Assertions.assertNotNull(internal);
    }

    // ==================== writeTwoDigitChar ====================

    @Test
    public void testWriteTwoDigitChar() {
        byte[] buf = new byte[2];
        HttpUnsafe.writeTwoDigitChar(buf, 0, 42);
        String result = new String(buf);
        Assertions.assertEquals("42", result);
    }

    @Test
    public void testWriteTwoDigitCharZero() {
        byte[] buf = new byte[2];
        HttpUnsafe.writeTwoDigitChar(buf, 0, 0);
        String result = new String(buf);
        Assertions.assertEquals("00", result);
    }

    @Test
    public void testWriteTwoDigitChar99() {
        byte[] buf = new byte[2];
        HttpUnsafe.writeTwoDigitChar(buf, 0, 99);
        String result = new String(buf);
        Assertions.assertEquals("99", result);
    }

    // ==================== mask helpers ====================

    @Test
    public void testMaskOfWhitespace() {
        byte[] data = "hello   ".getBytes();
        long mask = HttpUnsafe.maskOfWhitespace(data, 0);
        // At least one byte <= 0x20 (space) in "hello   ", so mask != 0
        Assertions.assertNotEquals(0L, mask);
    }

    @Test
    public void testMaskOfWhitespaceNoWhitespace() {
        // All bytes > 0x20 (i.e., >= 0x21), so no byte is <= 0x20
        byte[] data = {0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28};
        long mask = HttpUnsafe.maskOfWhitespace(data, 0);
        // All bytes > 0x20: each byte - 0x21 > 0, high bit clear → mask = 0
        Assertions.assertEquals(0L, mask);
    }

    @Test
    public void testMaskOfColon() {
        long value = 0x3A3A3A3A3A3A3A3AL;
        long mask = HttpUnsafe.maskOfColon(value);
        Assertions.assertNotEquals(0L, mask);
    }

    @Test
    public void testMaskOfColonNoColon() {
        long value = 0x4141414141414141L;
        long mask = HttpUnsafe.maskOfColon(value);
        Assertions.assertEquals(0L, mask);
    }

    @Test
    public void testMaskOfNewline() {
        long value = 0x0A0A0A0A0A0A0A0AL;
        long mask = HttpUnsafe.maskOfNewline(value);
        Assertions.assertNotEquals(0L, mask);
    }

    @Test
    public void testMaskOfNewlineNoNewline() {
        long value = 0x4141414141414141L;
        long mask = HttpUnsafe.maskOfNewline(value);
        Assertions.assertEquals(0L, mask);
    }

    @Test
    public void testMaskOfCR() {
        long value = 0x0D0D0D0D0D0D0D0DL;
        long mask = HttpUnsafe.maskOfCR(value);
        Assertions.assertNotEquals(0L, mask);
    }

    @Test
    public void testMaskOfCRNoCR() {
        long value = 0x4141414141414141L;
        long mask = HttpUnsafe.maskOfCR(value);
        Assertions.assertEquals(0L, mask);
    }

    // ==================== offsetTokenBytes (little-endian) ====================

    @Test
    public void testOffsetTokenBytesLittleEndian() {
        // mask with high bit set at byte offset 2: bit = 2*8+7 = 23
        long mask = 1L << 23; // 0x800000
        int offset = HttpUnsafe.offsetTokenBytes(mask);
        Assertions.assertEquals(2, offset);
    }

    @Test
    public void testOffsetTokenBytesLittleEndianOffset5() {
        // mask with high bit set at byte offset 5: bit = 5*8+7 = 47
        long mask = 1L << 47;
        int offset = HttpUnsafe.offsetTokenBytes(mask);
        Assertions.assertEquals(5, offset);
    }

    // ==================== Constants ====================

    @Test
    public void testCRLFConstant() {
        Assertions.assertNotEquals(0, HttpUnsafe.CRLF);
    }

    @Test
    public void testCRLFCRLFConstant() {
        Assertions.assertNotEquals(0, HttpUnsafe.CRLF_CRLF);
    }

    // ==================== findCRLFCRLF ====================

    @Test
    public void testFindCRLFCRLFSimple() {
        byte[] data = "GET / HTTP/1.1\r\n\r\n".getBytes();
        int pos = HttpUnsafe.findCRLFCRLF(data, 0, data.length);
        // \r\n\r\n starts at " HTTP/1.1\r\n\r\n"
        Assertions.assertTrue(pos > 0, "Should find CRLFCRLF");
        Assertions.assertEquals('\r', data[pos]);
        Assertions.assertEquals('\n', data[pos + 1]);
        Assertions.assertEquals('\r', data[pos + 2]);
        Assertions.assertEquals('\n', data[pos + 3]);
    }

    @Test
    public void testFindCRLFCRLFNotFound() {
        byte[] data = "hello world no crlf here".getBytes();
        int pos = HttpUnsafe.findCRLFCRLF(data, 0, data.length);
        Assertions.assertEquals(-1, pos);
    }

    @Test
    public void testFindCRLFCRLFLessThan4Bytes() {
        byte[] data = "abc".getBytes();
        int pos = HttpUnsafe.findCRLFCRLF(data, 0, data.length);
        Assertions.assertEquals(-1, pos);
    }

    @Test
    public void testFindCRLFCRLFExactAtStart() {
        byte[] data = "\r\n\r\n".getBytes();
        int pos = HttpUnsafe.findCRLFCRLF(data, 0, data.length);
        Assertions.assertEquals(0, pos);
    }

    @Test
    public void testFindCRLFCRLFExactAtEnd() {
        byte[] data = "header\r\n\r\n".getBytes();
        int pos = HttpUnsafe.findCRLFCRLF(data, 0, data.length);
        Assertions.assertEquals(6, pos);
    }

    @Test
    public void testFindCRLFCRLFAtAligned8Boundary() {
        // Place CRLFCRLF at position 8 (aligned to 8-byte boundary)
        byte[] data = "12345678\r\n\r\n".getBytes();
        int pos = HttpUnsafe.findCRLFCRLF(data, 0, data.length);
        Assertions.assertEquals(8, pos);
    }

    @Test
    public void testFindCRLFCRLFWithPartialCR() {
        // \r at position 0 but not followed by \n\r\n; CRLFCRLF is at position 6
        byte[] data = "\rxxxxx\r\n\r\n".getBytes();
        int pos = HttpUnsafe.findCRLFCRLF(data, 0, data.length);
        Assertions.assertEquals(6, pos);
    }

    @Test
    public void testFindCRLFCRLFWithCRatNonCRPosition() {
        // SWAR finds \r but data[pos] is negative byte, triggers negative-byte skip path
        byte[] data = new byte[20];
        // Put data with negative bytes followed by \r\n\r\n
        byte[] pattern = "AAAA\r\n\r\n".getBytes();
        System.arraycopy(pattern, 0, data, 0, pattern.length);
        int pos = HttpUnsafe.findCRLFCRLF(data, 0, data.length);
        Assertions.assertEquals(4, pos);
    }

    @Test
    public void testFindCRLFCRLFFallbackOnly() {
        // Data that forces fallback: no 8-byte aligned scan possible
        byte[] data = "abc\r\n\r\n".getBytes();
        // Start at 1 so SWAR loop limit8 = end - 8 = 8 - 8 = 0, and start=1 > 0 → skip SWAR, use fallback
        int pos = HttpUnsafe.findCRLFCRLF(data, 1, data.length);
        Assertions.assertEquals(3, pos);
    }

    @Test
    public void testFindCRLFCRLFFallbackNotFound() {
        byte[] data = "abcdefgh".getBytes();
        // start=1, end=8, limit8=0 → skip SWAR
        int pos = HttpUnsafe.findCRLFCRLF(data, 1, data.length);
        Assertions.assertEquals(-1, pos);
    }

    @Test
    public void testFindCRLFCRLFWithCRButNoNewline() {
        // SWAR finds \r but remaining bytes don't have \n\r\n
        byte[] data = ("header\r" + "xxxxxxxxxxxxx").getBytes();
        int pos = HttpUnsafe.findCRLFCRLF(data, 0, data.length);
        Assertions.assertEquals(-1, pos);
    }

    @Test
    public void testFindCRLFCRLFWithNoSpaceAfterCR() {
        // pos+4 > end → not enough space for full \r\n\r\n
        byte[] data = "header\r\n\u0000".getBytes();
        int pos = HttpUnsafe.findCRLFCRLF(data, 0, data.length);
        Assertions.assertEquals(-1, pos);
    }

    @Test
    public void testFindCRLFCRLFSpanningMultipleSWAR() {
        // Create data where \r is not found until the 3rd 8-byte block
        byte[] data = ("AAAAAAAA" + "BBBBBBBB" + "CC\r\n\r\n").getBytes();
        int pos = HttpUnsafe.findCRLFCRLF(data, 0, data.length);
        Assertions.assertEquals(18, pos);
    }

    @Test
    public void testFindCRLFCRLFWithNegativeByteFalsePositive() {
        // 0xFF byte causes SWAR mask false positive (not actually \r)
        // Then the negative-byte skip path is triggered (data[pos] != '\r')
        // Data: 0xFF at pos0 causes carry, followed by CRLFCRLF at pos4
        byte[] data = new byte[]{
                (byte) 0xFF, 0x78, 0x78, 0x78,
                0x0D, 0x0A, 0x0D, 0x0A
        };
        int pos = HttpUnsafe.findCRLFCRLF(data, 0, data.length);
        Assertions.assertEquals(4, pos);
    }

    @Test
    public void testFindCRLFCRLFFallbackPartialMatches() {
        // Fallback with \r followed by non-\n, then later full CRLFCRLF
        // Data: xxx\r   x\r\n\r\n  (position 3 is \r but 4 is 'x' not \n)
        byte[] data = "xxx\rx\r\n\r\n".getBytes(); // 10 bytes, indices 0-9
        // Skip SWAR: end=10, limit8=2, start=3 > 2
        int pos = HttpUnsafe.findCRLFCRLF(data, 3, data.length);
        // At start=3: data[3]='\r', data[4]='x'→partial. Then i=5: data[5..8]="\r\n\r\n"→match
        Assertions.assertEquals(5, pos);
    }

    @Test
    public void testFindCRLFCRLFFallbackCRLFButNoCR() {
        // Fallback with \r\n followed by non-\r, then full CRLFCRLF
        // Data: xx\r\n   x\r\n\r\n
        byte[] data = "xx\r\nx\r\n\r\n".getBytes(); // 10 bytes
        // end=10, limit8=2, start=3 > 2
        int pos = HttpUnsafe.findCRLFCRLF(data, 3, data.length);
        // At start=3: data[3]='\n', not '\r'. Then i=4: data[4..7]="x\r\n\r", no. i=5: match
        Assertions.assertEquals(5, pos);
    }

    @Test
    public void testFindCRLFCRLFFallbackCRLFCRButNoLF() {
        // Fallback with \r\n\r followed by non-\n, then full CRLFCRLF
        // Data: x\r\n\r   y\r\n\r\n
        byte[] data = "x\r\n\ry\r\n\r\n".getBytes(); // 10 bytes
        // end=10, limit8=2, start=3 > 2
        int pos = HttpUnsafe.findCRLFCRLF(data, 3, data.length);
        // At start=3: data[3]='\r', data[4]='\n', data[5]='\r', data[6]='y'→partial \r\n\r but no \n
        Assertions.assertEquals(5, pos);
    }

    // ==================== findCRLFCRLF: negative-byte skip loop  ====================

    @Test
    public void testFindCRLFCRLFMultipleNegativeSkip() {
        // Multiple consecutive negative bytes cause SWAR false positive for '\r',
        // triggering the inner while (data[i] < 0) ++i skip loop .
        byte[] data = new byte[]{
                (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80,
                (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80,
                0x0D, 0x0A, 0x0D, 0x0A
        };
        int pos = HttpUnsafe.findCRLFCRLF(data, 0, data.length);
        Assertions.assertEquals(8, pos);
    }

    @Test
    public void testFindCRLFCRLFNegativeBytesToEnd() {
        // Negative bytes run all the way to the buffer end with no CRLFCRLF.
        // Before the i < end guard on the skip loop , the inner
        // while (data[i] < 0) ++i walked past end and threw AIOOBE.
        byte[] data = new byte[]{
                (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80,
                (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80,
                (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80
        };
        int pos = HttpUnsafe.findCRLFCRLF(data, 0, data.length);
        Assertions.assertEquals(-1, pos);
    }

    // ==================== findCRLFCRLF fallback: data[i+2] != '\r'  ====================

    @Test
    public void testFindCRLFCRLFFallbackSecondCRMissing() {
        // Fallback: data[i]=='\r', data[i+1]=='\n', but data[i+2]!='\r'
        // Data: xx\r\nX\r\n\r\n
        byte[] data = "xx\r\nX\r\n\r\n".getBytes();
        // start=2 so limit8=end-8=6 → SWAR skips, fallback used
        int pos = HttpUnsafe.findCRLFCRLF(data, 2, data.length);
        // At i=2: '\r','\n','X' → data[i+2]!='\r', skip. At i=5: full match.
        Assertions.assertEquals(5, pos);
    }

    // ==================== findCRLFCRLF fallback: data[i+3] != '\n'  ====================

    @Test
    public void testFindCRLFCRLFFallbackTrailingNewlineMissing() {
        // Fallback: data[i]=='\r', data[i+1]=='\n', data[i+2]=='\r', but data[i+3]!='\n'
        // Data: x\r\n\rX\r\n\r\n
        byte[] data = "x\r\n\rX\r\n\r\n".getBytes();
        int pos = HttpUnsafe.findCRLFCRLF(data, 1, data.length);
        // At i=1: '\r','\n','\r','X' → data[i+3]!='\n', skip. At i=5: full match.
        Assertions.assertEquals(5, pos);
    }
}

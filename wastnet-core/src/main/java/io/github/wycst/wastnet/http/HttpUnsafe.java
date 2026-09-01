package io.github.wycst.wastnet.http;

import io.github.wycst.wastnet.env.RuntimeEnv;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.nio.ByteOrder;

public final class HttpUnsafe {
    private HttpUnsafe() {
    }
    static final long BYTE_ARRAY_OFFSET;
    static final long STRING_VALUE_OFFSET;
    static final Unsafe UNSAFE;
    static final boolean JDK_9_PLUS = RuntimeEnv.JDK_VERSION >= 9.0f;
    static final boolean LITTLE_ENDIAN = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;

    static {
        Unsafe instance;
        try {
            Field theUnsafeField = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafeField.setAccessible(true);
            instance = (Unsafe) theUnsafeField.get(null);
            clearUnsafeWarning();
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
        UNSAFE = instance;
        Field valueField;
        long valueOffset = -1;
        try {
            valueField = String.class.getDeclaredField("value");
            valueOffset = UNSAFE.objectFieldOffset(valueField);
        } catch (Exception ignored) {
        }
        STRING_VALUE_OFFSET = valueOffset;
        BYTE_ARRAY_OFFSET = UNSAFE.arrayBaseOffset(byte[].class);
    }
    private static final short[] TWO_DIGIT_CHAR_CACHE = new short[100];
    static {
        int index = 0;
        for (int tens = 0; tens < 10; tens++) {
            for (int units = 0; units < 10; units++) {
                int asciiTens = tens + 48;
                int asciiUnits = units + 48;
                TWO_DIGIT_CHAR_CACHE[index++] = LITTLE_ENDIAN ?
                        (short) ((asciiUnits << 8) | asciiTens) :
                        (short) ((asciiTens << 8) | asciiUnits);
            }
        }
    }

    private static void clearUnsafeWarning() {
        if (RuntimeEnv.JDK_VERSION >= 25) {
            try { // suppress unsafe warnings
                Field field = Unsafe.class.getDeclaredField("memoryAccessWarned");
                field.setAccessible(true);
                field.set(null, true);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Create a single-byte encoded string from the given byte array region.
     *
     * @param buf    source byte array
     * @param offset start offset in {@code buf}
     * @param len    number of bytes to encode
     * @return a String whose internal storage matches the source bytes
     */
    static String createAsciiString0(byte[] buf, int offset, int len) {
        if (JDK_9_PLUS) {
            byte[] result = new byte[len];
            copyOfRange(buf, offset, result, 0, len);
            return createAsciiStringJDK9(result);
        } else {
            // use chars
            char[] chars = new char[len];
            for (int i = 0; i < len; ++i) {
                chars[i] = (char) (buf[i + offset] & 0xFF);
            }
            return createStringJDK8(chars);
        }
    }

    /**
     * Create a single-byte encoded string from the given byte array region,
     * with bounds checking to prevent array index out of bounds.
     *
     * @param buf    source byte array
     * @param offset start offset in {@code buf}
     * @param len    number of bytes to encode
     * @return a String whose internal storage matches the source bytes
     * @throws NullPointerException      if {@code buf} is null
     * @throws IndexOutOfBoundsException if offset or len is invalid
     */
    public static String createAsciiString(byte[] buf, int offset, int len) {
        if (offset < 0 || len < 0 || len > buf.length - offset) {
            throw new IndexOutOfBoundsException("offset=" + offset + ", len=" + len + ", buf.length=" + buf.length);
        }
        return createAsciiString0(buf, offset, len);
    }

    public static String createAsciiString(byte[] buf) {
        if(buf.length == 0) return "";
        if (JDK_9_PLUS) {
            return createAsciiStringJDK9(buf);
        } else {
            int len = buf.length;
            char[] chars = new char[len];
            for (int i = 0; i < len; ++i) {
                chars[i] = (char) (buf[i] & 0xFF);
            }
            return createStringJDK8(chars);
        }
    }

    static void copyOfRange(byte[] source, int srcOff, byte[] target, int targetOff, int len) {
        if (len < 32) {
            int i = 0, count = 0;
            long targetBAO = BYTE_ARRAY_OFFSET + targetOff;
            if (len > 15) {
                long v1 = UNSAFE.getLong(source, srcOff + BYTE_ARRAY_OFFSET), v2 = UNSAFE.getLong(source, 8 + srcOff + BYTE_ARRAY_OFFSET);
                UNSAFE.putLong(target, targetBAO, v1);
                UNSAFE.putLong(target, targetBAO + 8, v2);
                count += 16;
                i = 16;
            }
            if (i <= len - 8) { // i <= len - 8
                UNSAFE.putLong(target, count + targetBAO, UNSAFE.getLong(source, i + srcOff + BYTE_ARRAY_OFFSET));
                count += 8;
                i += 8;
            }
            if (i <= len - 4) {
                UNSAFE.putInt(target, count + targetBAO, UNSAFE.getInt(source, i + srcOff + BYTE_ARRAY_OFFSET));
                count += 4;
                i += 4;
            }
            if (i <= len - 2) {
                UNSAFE.putShort(target, count + targetBAO, UNSAFE.getShort(source, i + srcOff + BYTE_ARRAY_OFFSET));
                count += 2;
                i += 2;
            }
            if (i < len) { // i <= len - 1
                target[count + targetOff] = source[i + srcOff];
            }
        } else {
            System.arraycopy(source, srcOff, target, targetOff, len);
        }
    }

    static String createAsciiStringJDK9(byte[] asciiBytes) {
        String result = new String();
        UNSAFE.putObject(result, STRING_VALUE_OFFSET, asciiBytes);
        return result;
    }

    static String createStringJDK8(char[] buf) {
        String target = new String();
        UNSAFE.putObject(target, STRING_VALUE_OFFSET, buf);
        return target;
    }

    static long getLong(byte[] buf, int offset) {
        return UNSAFE.getLong(buf, BYTE_ARRAY_OFFSET + offset);
    }

    static short getShort(byte[] buf, int offset) {
        return UNSAFE.getShort(buf, BYTE_ARRAY_OFFSET + offset);
    }

    static int getInt(byte[] buf, int offset) {
        return UNSAFE.getInt(buf, BYTE_ARRAY_OFFSET + offset);
    }

    static Object getStringValue(String value) {
        return UNSAFE.getObject(value, STRING_VALUE_OFFSET);
    }

    /**
     * Write a two-digit number (as ASCII characters) into a byte array.
     *
     * @param buf    target byte array
     * @param offset write offset
     * @param value  two-digit number (0-99, 0 -> "00")
     */
    static void writeTwoDigitChar(byte[] buf, int offset, int value) {
        UNSAFE.putShort(buf, BYTE_ARRAY_OFFSET + offset, TWO_DIGIT_CHAR_CACHE[value]);
    }

    // if 0x8080808080808080L then bytes(8) all > 32, otherwise exist the byte <= 32 (or negative byte)
    static long maskOfWhitespace(byte[] buf, int offset) {
        return getLong(buf, offset) - 0x2121212121212121L & 0x8080808080808080L;
    }

    // if 0 then bytes(8) all != ':'(0x3A), otherwise exist the byte == ':' (or negative byte)
    static long maskOfColon(long value) {
        return (value ^ 0x3A3A3A3A3A3A3A3AL) - 0x0101010101010101L & 0x8080808080808080L;
    }

    // if 0 then bytes(8) all != '\n'(0x0A), otherwise exist the byte == '\n' (or negative byte)
    static long maskOfNewline(long value) {
        return (value ^ 0x0A0A0A0A0A0A0A0AL) - 0x0101010101010101L & 0x8080808080808080L;
    }

    // if 0 then bytes(8) all != '\r'(0x0D), otherwise exist the byte == '\r' (or negative byte)
    static long maskOfCR(long value) {
        return (value ^ 0x0D0D0D0D0D0D0D0DL) - 0x0101010101010101L & 0x8080808080808080L;
    }

    // \r\n as short value
    static final short CRLF = getShort(new byte[]{'\r', '\n'}, 0);

    // \r\n\r\n as int value
    static final int CRLF_CRLF = CRLF | (CRLF << 16);

    static int offsetTokenBytes(long mask) {
        if (LITTLE_ENDIAN) {
            return Long.numberOfTrailingZeros(mask) >> 3;
        } else {
            return Long.numberOfLeadingZeros(mask) >> 3;
        }
    }

    /**
     * Find the position of first "\r\n\r\n" pattern using SWAR optimization.
     *
     * @param data  byte array to search
     * @param start start position (inclusive)
     * @param end   end position (exclusive)
     * @return position of first "\r\n\r\n", or -1 if not found
     */
    static int findCRLFCRLF(byte[] data, int start, int end) {
        if (start + 4 > end) return -1;
        int i = start;
        final int limit8 = end - 8;
        // SWAR fast path: scan 8 bytes at a time for '\r'
        while (i <= limit8) {
            long mask = maskOfCR(getLong(data, i));
            if (mask != 0) {
                int offset = offsetTokenBytes(mask);
                int pos = i + offset;
                if (data[pos] != '\r') {
                    // maybe negative bytes; guard i < end to avoid out-of-bounds
                    i = pos + 1;
                    while (i < end && data[i] < 0) ++i;
                    continue;
                }
                // Check if remaining bytes available for "\r\n\r\n"
                if (pos + 4 <= end && getInt(data, pos) == CRLF_CRLF) {
                    return pos;
                }
                // Move to next possible position
                i = pos + 1;
            } else {
                i += 8;
            }
        }
        // Fallback: check remaining bytes one by one
        for (int limit4 = end - 4; i <= limit4; ++i) {
            if (getInt(data, i) == CRLF_CRLF) {
                return i;
            }
        }
        return -1;
    }
}

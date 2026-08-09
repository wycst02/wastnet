package io.github.wycst.wastnet.util;

import java.nio.charset.Charset;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @since 2024-4-28
 * @author wangyc
 */
public final class Utils {
    private Utils() {}
    static final char[] HEX_CHARS = "0123456789ABCDEF".toCharArray();
    static final AtomicLong ATOMIC_LONG = new AtomicLong();

    // Hex reverse lookup table: len: 103
    static final byte[] HEX_DIGITS_RE = {
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, 0, 1,
            2, 3, 4, 5, 6, 7, 8, 9, -1, -1,
            -1, -1, -1, -1, -1, 10, 11, 12, 13, 14,
            15, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, 10, 11, 12,
            13, 14, 15
    };

    // Hex digits 0-15 (0-9, a-f): len: 16
    static final byte[] HEX_BYTES = {
            48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 97, 98, 99, 100, 101, 102
    };

    public static final Charset UTF_8 = forCharsetName("UTF-8");
    public static final Charset ISO_8859_1 = forCharsetName("ISO_8859_1");

    public static Charset forCharsetName(String charsetName) {
        try {
            return Charset.forName(charsetName);
        } catch (Throwable throwable) {
            throw new IllegalArgumentException("Unsupported charset: " + charsetName);
        }
    }

    public static Charset forCharsetName(String charsetName, Charset defaultCharset) {
        try {
            return Charset.forName(charsetName);
        } catch (Throwable throwable) {
            return defaultCharset;
        }
    }

    public static void shutdownExecutorService(ExecutorService executorService) {
        try {
            executorService.shutdown();
            if (!executorService.awaitTermination(5000L, TimeUnit.MILLISECONDS)) {
                executorService.shutdownNow();
            }
        } catch (Throwable var4) {
            try {
                executorService.shutdownNow();
            } catch (Throwable ignored) {
            }
        }

    }

    /**
     * Convert long value to 16-character hex string with leading zeros.
     *
     * @param value the value to convert
     * @return hex string representation
     * @see Long#toHexString(long)
     */
    public static String toHexString16(long value) {
        char[] chars = new char[16];
        for (int i = 15; i > -1; --i) {
            int val = (int) (value & 0xf);
            chars[i] = HEX_CHARS[val];
            value >>= 4L;
        }
        return new String(chars);
    }

    /**
     * Generate a unique 16-character hex string ID.
     */
    public static String hex() {
        return toHexString16(ATOMIC_LONG.incrementAndGet());
    }

    /**
     * Generate a unique long ID.
     */
    public static long id() {
        return ATOMIC_LONG.incrementAndGet();
    }

    // FNV-1a hash prime
    private static final long FNV_64_PRIME = 0x100000001b3L;

    /**
     * FNV-1a hash extension for HPACK hash table.
     */
    public static long fnv64(long hash, long total) {
        long rv = hash;
        rv ^= total;
        rv *= FNV_64_PRIME;
        return rv;
    }

    /**
     * Print byte array as hex string.
     *
     * @param b         byte array
     * @param splitChar split character, or 0 for no split
     * @return hex string
     */
    public static String printHexString(byte[] b, char splitChar) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < b.length; ++i) {
            String hex = Integer.toHexString(b[i] & 255);
            if (hex.length() == 1) {
                builder.append('0');
            }
            builder.append(hex.toUpperCase());
            if (splitChar > 0) {
                builder.append(splitChar);
            }
        }
        return builder.toString();
    }

    /**
     * Parse an ASCII code point as a hexadecimal character and return its nibble value (0-15).
     * <p>
     * For example, {@code hexNibble('A')} returns {@code 10},
     * {@code hexNibble('3')} returns {@code 3}.
     * <p>
     * <b>Framework internal usage:</b> The callers ensure {@code c >= 0} (via {@code b & 0xFF} masking),
     * so the array bounds check is implicitly satisfied for valid hex chars.
     * <b>External callers:</b> Ensure {@code c} is within {@code [0, 102]} (the length of the lookup table),
     * otherwise accessing {@code HEX_DIGITS_RE[c]} may throw {@link ArrayIndexOutOfBoundsException}.
     *
     * @param c an ASCII code point representing a hex character ('0'-'9', 'A'-'F', 'a'-'f')
     * @return the nibble value (0-15), or -1 if the code point is not a valid hex character
     */
    public static byte hexNibble(int c) {
        if (c < HEX_DIGITS_RE.length) {
            return HEX_DIGITS_RE[c];
        }
        return -1;
    }

    /**
     * Convert integer value to hex bytes
     *
     * @param value    integer value (supports both positive and negative)
     * @param hexBytes byte array to store hex representation
     * @param offset   starting offset in hexBytes
     * @return number of bytes written to hexBytes
     */
    public static int intToHexBytes(int value, byte[] hexBytes, int offset) {
        // Special case for zero
        if (value == 0) {
            hexBytes[offset] = '0';
            return 1;
        }
        // Calculate number of hex digits needed using ceiling division
        int len = 35 - Integer.numberOfLeadingZeros(value) >> 2;
        for (int i = 0; i < len; ++i) {
            // len - i
            int shift = (len - i - 1) << 2;
            hexBytes[offset + i] = HEX_BYTES[value >> shift & 0xF];
        }
        return len;
    }

    /**
     * Encode URI path: percent-encode non-safe characters while preserving
     * URI structural characters ({@code / ? = & #}) intact.
     * <p>
     * Safe characters (kept as-is): {@code A-Z a-z 0-9 - _ . ~ / ? = & #}
     * Space is encoded as {@code %20} (not {@code +}).
     * All non-ASCII chars are UTF-8 encoded then percent-encoded.
     * <p>
     * Pure ASCII safe input returns the original string directly (zero allocation).
     *
     * @param uri the URI string to encode
     * @return URI string with non-safe characters percent-encoded
     */
    public static String encodeUriPath(String uri) {
        int needsEncode = -1;
        for (int i = 0; i < uri.length(); ++i) {
            char c = uri.charAt(i);
            if (c > 0x7F || !isUriPathSafe(c)) {
                needsEncode = i;
                break;
            }
        }
        if (needsEncode == -1) return uri;

        StringBuilder sb = new StringBuilder(uri.length() + 16);
        sb.append(uri, 0, needsEncode);
        byte[] utf8Buf = null;
        for (int i = needsEncode; i < uri.length(); ++i) {
            char c = uri.charAt(i);
            if (c <= 0x7F && isUriPathSafe(c)) {
                if (c == ' ') {
                    sb.append("%20");
                } else {
                    sb.append(c);
                }
            } else {
                if (utf8Buf == null) utf8Buf = new byte[4];
                int len = charToUtf8(c, i + 1 < uri.length() ? uri.charAt(i + 1) : '\0', utf8Buf);
                for (int j = 0; j < len; j++) {
                    sb.append('%');
                    sb.append(HEX_CHARS[(utf8Buf[j] >> 4) & 0x0F]);
                    sb.append(HEX_CHARS[utf8Buf[j] & 0x0F]);
                }
                if (Character.isHighSurrogate(c) && i + 1 < uri.length() && Character.isLowSurrogate(uri.charAt(i + 1))) {
                    ++i;
                }
            }
        }
        return sb.toString();
    }

    private static boolean isUriPathSafe(char c) {
        if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) return true;
        if (c == '-' || c == '_' || c == '.' || c == '~') return true;
        return c == '/' || c == '?' || c == '=' || c == '&' || c == '#';
    }

    private static int charToUtf8(char c, char nextChar, byte[] out) {
        int codePoint;
        if (Character.isHighSurrogate(c) && Character.isLowSurrogate(nextChar)) {
            codePoint = Character.toCodePoint(c, nextChar);
        } else {
            codePoint = c;
        }
        if (codePoint < 0x80) {
            out[0] = (byte) codePoint;
            return 1;
        } else if (codePoint < 0x800) {
            out[0] = (byte) (0xC0 | (codePoint >> 6));
            out[1] = (byte) (0x80 | (codePoint & 0x3F));
            return 2;
        } else if (codePoint < 0x10000) {
            out[0] = (byte) (0xE0 | (codePoint >> 12));
            out[1] = (byte) (0x80 | ((codePoint >> 6) & 0x3F));
            out[2] = (byte) (0x80 | (codePoint & 0x3F));
            return 3;
        } else {
            out[0] = (byte) (0xF0 | (codePoint >> 18));
            out[1] = (byte) (0x80 | ((codePoint >> 12) & 0x3F));
            out[2] = (byte) (0x80 | ((codePoint >> 6) & 0x3F));
            out[3] = (byte) (0x80 | (codePoint & 0x3F));
            return 4;
        }
    }

    /**
     * Escape special characters in string value.
     *
     * @param s the string to escape
     * @return escaped string
     */
    public static String escapeSpecialString(String s) {
        if (s == null) return "";
        int len = s.length();
        StringBuilder sb = new StringBuilder(len + 16);
        for (int i = 0; i < len; ++i) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < ' ') {
                        sb.append("\\u00");
                        sb.append(HEX_CHARS[(c >> 4) & 0x0F]);
                        sb.append(HEX_CHARS[c & 0x0F]);
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}

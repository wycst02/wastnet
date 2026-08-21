/*
 * Copyright 2026, wangyunchao.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.wycst.wastnet.http.h2;

import io.github.wycst.wastnet.http.HttpConf;
import io.github.wycst.wastnet.http.HttpUnsafe;
import io.github.wycst.wastnet.util.Utils;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.*;

/**
 * HTTP/2 HPACK encoder and decoder.
 *
 * <p>Manages static and dynamic tables. One instance per TCP connection, not thread-safe.</p>
 *
 * @author wangyc
 */
public final class Http2HpackCodec {

    public final static Charset UTF_8 = Utils.UTF_8;

    /**
     * Upper bound for a dynamic table size update (RFC 7541 §6.3).
     * Must equal the SETTINGS_HEADER_TABLE_SIZE this server advertises, so the
     * decoder enforces exactly what the peer was allowed to request.
     */
    public static final int MAX_DYNAMIC_TABLE_SIZE = HttpConf.HTTP2_MAX_HEADER_TABLE_SIZE;

    private final Http2HpackTable decodeHpackTable;
    private final Http2HpackTable encodeHpackTable; // reserved for outbound dynamic indexing
    private final Charset charset;

    // RFC 7540 §8.1.2.1: valid request pseudo-header fields (decoded by a server)
    private static final Set<String> SERVER_PSEUDO_HEADERS = new HashSet<String>(Arrays.asList(
            ":method", ":scheme", ":path", ":authority"));
    // RFC 7540 §8.1.2.1: valid response pseudo-header fields (decoded by a client)
    private static final Set<String> CLIENT_PSEUDO_HEADERS = new HashSet<String>(Arrays.asList(
            ":status"));

    // Pseudo-header fields accepted for the current direction; defaults to server (request) side.
    private Set<String> allowedPseudoHeaders = SERVER_PSEUDO_HEADERS;

    // When true (default), header blocks are validated for pseudo-header ordering and
    // unknown/forbidden pseudo-headers during decoding (RFC 7540 §8.1.2.1).
    private boolean validate = true;

    final int[] countRef = {0};
    private byte[] huffmanDeOutputCache = new byte[64];
    // Transient decode state: whether a regular (non-pseudo) header has been seen yet.
    // Used to enforce that pseudo-headers precede regular headers within a single block.
    private boolean sawRegularHeader;

    public Http2HpackCodec() {
        this(4096, UTF_8);
    }

    public Http2HpackCodec(int maxHeaderSize) {
        this(maxHeaderSize, UTF_8);
    }

    public Http2HpackCodec(Charset charset) {
        this(4096, charset);
    }

    public Http2HpackCodec(int maxHeaderSize, Charset charset) {
        decodeHpackTable = new Http2HpackTable(maxHeaderSize);
        encodeHpackTable = new Http2HpackTable(maxHeaderSize);
        this.charset = charset;
    }

    /**
     * Enable or disable header-block validation (RFC 7540 §8.1.2.1): pseudo-header ordering
     * and rejection of unknown/forbidden pseudo-headers. Enabled by default.
     */
    public void setValidate(boolean validate) {
        this.validate = validate;
    }

    /**
     * Switch this codec to client (response) decoding mode: only {@code :status} is accepted
     * as a valid pseudo-header. By default a codec is in server (request) mode, accepting
     * {@code :method}, {@code :scheme}, {@code :path}, {@code :authority}.
     *
     * @return this codec, for chaining
     */
    public Http2HpackCodec client() {
        this.allowedPseudoHeaders = CLIENT_PSEUDO_HEADERS;
        return this;
    }

    // ==================== HPACK 7-bit prefix integer encoding (RFC 7541 §5.1) ====================

    /**
     * Encode a length value using HPACK 7-bit prefix encoding (RFC 7541, section 5.1).
     * <p>
     * Convenience overload: Huffman-flagged (backward compatible).
     *
     * @param length the integer value to encode (non-negative)
     * @param output the output byte array
     * @param outOff the offset into the output array where encoding begins
     * @return the number of bytes written to {@code output}
     */
    public static int encodeLength(int length, byte[] output, int outOff) {
        return encodeLength(length, output, outOff, true);
    }

    /**
     * Encode a length value using HPACK 7-bit prefix encoding (RFC 7541, section 5.1).
     * <p>
     * Sets bit 7 (H flag) according to the {@code huffman} parameter.
     *
     * @param length the integer value to encode (non-negative)
     * @param output the output byte array
     * @param outOff the offset into the output array where encoding begins
     * @param huffman whether to set the H flag (bit 7 = 1 for Huffman, 0 for plain)
     * @return the number of bytes written to {@code output}
     */
    public static int encodeLength(int length, byte[] output, int outOff, boolean huffman) {
        return encodeLength(length, output, outOff, huffman ? 0x80 : 0);
    }

    /**
     * Encode a length value using HPACK 7-bit prefix encoding (RFC 7541, section 5.1).
     * <p>
     * Low-level implementation. The {@code flag} parameter carries bit 7 (H flag)
     * and is OR'd into the first byte.
     *
     * @param length the integer value to encode (non-negative)
     * @param output the output byte array
     * @param outOff the offset into the output array where encoding begins
     * @param flag   bit 7 value: 0x80 for Huffman, 0 for plain text
     * @return the number of bytes written to {@code output}
     */
    static int encodeLength(int length, byte[] output, int outOff, int flag) {
        final int begin = outOff;
        if (length < 127) {
            // Single byte: H-flag | 7-bit length value
            output[outOff++] = (byte) (flag | length);
        } else {
            // Prefix byte: H-flag | 0x7F (all 1s = continuation), followed by varint(length - 127)
            // Decoded as: length = 0x7F + b0 + b1*128 + b2*128^2 + ...
            output[outOff++] = (byte) (flag | 0x7F);
            length -= 0x7F;
            while (length >= 128) {
                output[outOff++] = (byte) ((length & 0x7F) | 0x80);
                length >>>= 7;
            }
            output[outOff++] = (byte) length;
        }
        return outOff - begin;
    }

    public void setMaxHeaderSize(int maxTableSize) {
        decodeHpackTable.setMaxHeaderSize(maxTableSize);
    }

    public Map<String, Object> decode(byte[] buf) {
        return decode(buf, 0, buf.length);
    }

    public void decodeTo(byte[] buf, Map<String, Object> headers) {
        decodeTo(buf, 0, buf.length, headers);
    }

    public Map<String, Object> decode(ByteBuffer buffer) {
        return decode(buffer.array(), buffer.position(), buffer.remaining());
    }

    public void decodeTo(ByteBuffer buffer, Map<String, Object> headers) {
        decodeTo(buffer.array(), buffer.position(), buffer.remaining(), headers);
    }

    public Map<String, Object> decode(byte[] buf, int offset, int len) {
        Map<String, Object> headers = new LinkedHashMap<String, Object>();
        try {
            decodeTo(buf, offset, len, headers);
        } catch (Throwable throwable) {
            throw throwable instanceof Http2HpackException ? (Http2HpackException) throwable : new Http2HpackException("hpack decode error:  " + throwable.getMessage(), throwable);
        }
        return headers;
    }

    int decodeLength(byte[] buf, int offset, final int initValue, int[] countRef) {
        int bits = 0, value = initValue, cmd;
        while ((cmd = buf[offset++]) < 0) {
            value += (cmd & 0x7f) << bits;
            bits += 7;
            if (bits > 28) {
                throw new Http2HpackException("HPACK integer encoding exceeds maximum length");
            }
        }
        value += cmd << bits;
        countRef[0] = value;
        return offset;
    }

    int decodeString(byte[] buf, int offset, boolean isName, String[] result) {
        int count, cmd;
        final int[] countRef = this.countRef;
        if ((count = (cmd = buf[offset++]) & 0x7f) == 0x7f) {
            offset = decodeLength(buf, offset, count, countRef);
            count = countRef[0];
        }
        if (cmd < 0) {
            String decodeValue = "";
            if (!isName || count > MAX_CACHE_STRING_LEN) {
                if (offset + count > buf.length) {  // cap count to buffer size; blocks the len<<1 heap pre-allocation blow-up
                    throw new Http2HpackException("HPACK string length exceeds input buffer boundary");
                }
                decodeValue = decodeHuffmanString(buf, offset, count, countRef, isName);
            } else { // must header name
                if (count > 0) {
                    long hashBytes = hashBytes(buf, offset, count);
                    decodeValue = getCachedStringValue(buf, offset, count, hashBytes);
                    if (decodeValue == null) {
                        decodeValue = decodeHuffmanString(buf, offset, count, countRef, true);
                        setCachedValue(Arrays.copyOfRange(buf, offset, offset + count), decodeValue, hashBytes);
                    }
                }
                countRef[0] = decodeValue.length();
            }
            result[0] = decodeValue;
        } else {
            result[0] = createString(buf, offset, count, isName);
            countRef[0] = count;
        }
        return offset + count;
    }

    /**
     * Decode a complete HPACK header block (a normal request/response header block) into
     * {@code headers}. The block is expected to be a <i>full</i> header block — in particular it
     * MUST begin with the mandatory pseudo-header fields (e.g. {@code :method}, {@code :path},
     * {@code :authority} for a request), which are position-validated by
     * {@link #putHeader(Map, String, String)}. Equivalent to calling the 5-argument overload with
     * {@code trailer = false}.
     * <p>
     * To decode a trailer block (RFC 7540 §8.1), where pseudo-header fields MUST NOT appear, use
     * {@link #decodeTo(byte[], int, int, Map, boolean)} with {@code trailer = true}.
     */
    public void decodeTo(byte[] buf, int offset, int len, Map<String, Object> headers) {
        decodeTo(buf, offset, len, headers, false);
    }

    /**
     * Decode an HPACK header block into {@code headers}.
     * <pre>
     * HPACK first-byte encoding (RFC 7541):
     *
     *   A) 1xxxxxxx   → Indexed Header Field            (7-bit index)
     *   B) 01xxxxxx   → Incremental Indexing            (6-bit index, 0x40|N)
     *   C) 0000xxxx   → Without Indexing                (4-bit index, 0x00|N)
     *   D) 0001xxxx   → Never Indexed                   (4-bit index, 0x10|N)
     *   E) 001xxxxx   → Dynamic Table Size Update       (5-bit size,  0x20|S)
     *
     *   Extended integer: when prefix bits are ALL 1 (N=15/31/63),
     *   continuation bytes follow (MSB=1 more / MSB=0 last).
     * </pre>
     *
     * @param buf    the HPACK-encoded header block bytes
     * @param offset start offset within {@code buf}
     * @param len    number of bytes to decode, beginning at {@code offset}
     * @param headers the map to populate with the decoded header fields
     * @param trailer when {@code true}, the block is a trailer block (RFC 7540 §8.1):
     *                pseudo-header fields MUST NOT appear. This is enforced by initializing
     *                {@code sawRegularHeader} to {@code true}, so any ':'-prefixed field fails
     *                the pseudo-header position check in {@link #putHeader(Map, String, String)}.
     */
    public void decodeTo(byte[] buf, int offset, int len, Map<String, Object> headers, boolean trailer) {
        int endOffset = offset + len, index, cmd;
        try {
            Http2Header indexHeader;
            String name, value;
            final String[] result = {null};
            final int[] countRef = this.countRef;
            sawRegularHeader = trailer;
            while (offset < endOffset) {
                if ((cmd = buf[offset++]) < 0) {     // A) Indexed Header Field, cmd < 0 → bit 7 = 1
                    if ((index = cmd & 0x7f) == 0x7f) {
                        offset = decodeLength(buf, offset, index, countRef);
                        index = countRef[0];
                    }
                    indexHeader = decodeHpackTable.getHeader(index);
                    putHeader(headers, indexHeader.name, indexHeader.value);
                    continue;
                }
                if (cmd >= 64) {                 // B) Incremental Indexing, cmd ≥ 64 → bit 7-6 = 01
                    if (cmd == 64) {             // B-a index = 0 → inline name
                        offset = decodeString(buf, offset, true, result);
                        name = result[0];
                        int nameLen = countRef[0];
                        offset = decodeString(buf, offset, false, result);
                        value = result[0];
                        putHeader(headers, name, value);
                        decodeHpackTable.addHeader(new Http2Header(name, nameLen, value, countRef[0]));
                    } else {                     // B-b index > 0 → name ref
                        if ((index = cmd & 0x3f) == 0x3f) {
                            offset = decodeLength(buf, offset, index, countRef);
                            index = countRef[0];
                        }
                        indexHeader = decodeHpackTable.getHeader(index);
                        name = indexHeader.name;
                        offset = decodeString(buf, offset, false, result);
                        value = result[0];
                        putHeader(headers, name, value);
                        decodeHpackTable.addHeader(new Http2Header(name, indexHeader.nameLen, value, countRef[0]));
                    }
                    continue;
                }
                if (cmd < 32) {                  // C/D) Without Indexing / Never Indexed, cmd < 32 → bit 7-5 = 000
                    int mantissa = cmd & 0xF;    // C: bit 4 = 0 (0000xxxx), D: bit 4 = 1 (0001xxxx)
                    if (mantissa == 0) {         // index = 0 → inline name
                        offset = decodeString(buf, offset, true, result);
                        name = result[0];
                    } else {                     // index > 0 → name ref
                        index = mantissa;
                        if (index == 0xF) {      // all 4 bits = 1 → extended integer
                            offset = decodeLength(buf, offset, index, countRef);
                            index = countRef[0];
                        }
                        indexHeader = decodeHpackTable.getHeader(index);
                        name = indexHeader.name;
                    }
                    offset = decodeString(buf, offset, false, result);
                    value = result[0];
                    putHeader(headers, name, value);
                } else {                         // E) Dynamic Table Size Update, cmd ≥ 32 → bit 7-5 = 001
                    int maxSize = cmd & 0x1f;
                    if (maxSize == 0x1f) {       // all 5 bits = 1 → extended integer
                        offset = decodeLength(buf, offset, maxSize, countRef);
                        maxSize = countRef[0];
                    }
                    // RFC 7541 §4.2: a dynamic table size update MUST appear at the
                    // beginning of the header block, before any header field representation.
                    if (headers.size() > 0) {
                        throw new Http2HpackException("Dynamic table size update MUST appear before any header field (RFC 7541 §4.2)");
                    }
                    // RFC 7541 §6.3: the new maximum size MUST NOT exceed the protocol
                    // limit. We hard-cap it to bound decoder memory usage.
                    if (maxSize > MAX_DYNAMIC_TABLE_SIZE) {
                        throw new Http2HpackException("Dynamic table size update " + maxSize
                                + " exceeds hard limit " + MAX_DYNAMIC_TABLE_SIZE + " bytes (RFC 7541 §6.3)");
                    }
                    decodeHpackTable.setMaxHeaderSize(maxSize);
                }
            }
        } catch (RuntimeException throwable) {
            if (offset > endOffset) {
                throw new Http2HpackException("hpack decode error, the length of input bytes is wrong");
            }
            throw throwable;
        }
    }

    private void putHeader(Map<String, Object> headers, String name, String value) {
        if (validate) {
            // RFC 7540 §8.1.2.1:
            //  - header field names MUST NOT be empty;
            //  - pseudo-header fields MUST appear before all regular header fields;
            //  - only the known pseudo-header fields are valid (anything else is malformed).
            if (name.isEmpty()) {
                throw new Http2HpackException("Empty header field name (RFC 7540 §8.1.2)");
            }
            if (name.charAt(0) == ':') {
                if (sawRegularHeader) {
                    throw new Http2HpackException("Pseudo-header field appears after a regular header field: " + name);
                }
                if (!allowedPseudoHeaders.contains(name)) {
                    throw new Http2HpackException("Unknown or forbidden pseudo-header field: " + name);
                }
            } else {
                sawRegularHeader = true;
                // RFC 7540 §8.1.2.2: connection-specific header fields MUST NOT be produced by
                // an endpoint; they are forbidden regardless of direction (request or response).
                // Pre-filter by the first character to avoid equals() on the common path.
                boolean forbidden;
                switch (name.charAt(0)) {
                    case 'c': forbidden = name.equals("connection"); break;
                    case 'k': forbidden = name.equals("keep-alive"); break;
                    case 'p': forbidden = name.equals("proxy-connection"); break;
                    case 't': forbidden = name.equals("transfer-encoding"); break;
                    case 'u': forbidden = name.equals("upgrade"); break;
                    default: forbidden = false;
                }
                if (forbidden) {
                    throw new Http2HpackException("Forbidden connection-specific header field: " + name);
                }
            }
        }
        Object oldVal = headers.get(name);
        if (oldVal != null) {
            if (oldVal instanceof String) {
                List<String> values = new ArrayList<String>();
                values.add((String) oldVal);
                values.add(value);
                headers.put(name, values);
            } else {
                List<String> values = (List<String>) oldVal;
                values.add(value);
            }
        } else {
            headers.put(name, value);
        }
    }

    private final static CacheValueNode[] CACHE_VALUE_NODES = new CacheValueNode[1024];
    private final static int MASK = CACHE_VALUE_NODES.length - 1;
    private final static int MAX_CACHE_COUNT = 2048;
    private final static int MAX_CACHE_STRING_LEN = 48;
    private static int cachedCount = 0;

    static class CacheValueNode {
        public final byte[] source;
        public final String value;
        public final long hash;

        CacheValueNode(byte[] source, String value, long hash) {
            this.source = source;
            this.value = value;
            this.hash = hash;
        }

        CacheValueNode next;
    }

    static String getCachedStringValue(byte[] buf, int offset, int len, long hashValue) {
        int index = (int) (hashValue & MASK);
        CacheValueNode valueNode = CACHE_VALUE_NODES[index];
        if (valueNode == null) {
            return null;
        }
        while (!equals(buf, offset, len, valueNode.source)) {
            valueNode = valueNode.next;
            if (valueNode == null) {
                return null;
            }
        }
        return valueNode.value;
    }

    static void setCachedValue(byte[] source, String value, long hashValue) {
        synchronized (CACHE_VALUE_NODES) {
            if (cachedCount >= MAX_CACHE_COUNT) return;
            ++cachedCount;
        }
        int index = (int) (hashValue & MASK);
        CacheValueNode valueNode = new CacheValueNode(source, value, hashValue);
        CacheValueNode oldEntryNode = CACHE_VALUE_NODES[index];
        CACHE_VALUE_NODES[index] = valueNode;
        if (oldEntryNode != null) {
            valueNode.next = oldEntryNode;
        }
    }

    private static long hashBytes(byte[] buf, int offset, int len) {
        long hash = 0;
        int total = 0;
        for (int i = offset, endOffset = offset + len; i < endOffset; ++i) {
            int val = buf[i] & 0xFF;
            hash = (hash << 1) + val;
            total += val;
        }
        return Utils.fnv64(hash, total);
    }

    private static boolean equals(byte[] buf, int offset, int len, byte[] source) {
        if (len != source.length) return false;
        int i = 0;
        if ((len & 1) == 1) {
            if (buf[offset + i] != source[i]) return false;
            ++i;
            --len;
        }
        for (; i < len; i = i + 2) {
            if (buf[offset + i] != source[i]) return false;
            if (buf[offset + i + 1] != source[i + 1]) return false;
        }
        return true;
    }

    /**
     * Decode Huffman encoded bytes to string (UTF-8).
     *
     * @param buf      input buffer
     * @param offset   start offset
     * @param len      input length
     * @param countRef actual decoded length
     * @return decoded string
     */
    String decodeHuffmanString(byte[] buf, int offset, int len, int[] countRef, boolean isName) {
        try {
            int rlen = len << 1;
            byte[] output = allocateDeHuffmanOutput(rlen);
            int count = HuffmanByteCodec.decodeData(buf, offset, len, output, 0);
            countRef[0] = count;
            return createString(output, 0, count, isName);
        } catch (Throwable throwable) {
            String hex = Utils.printHexString(Arrays.copyOfRange(buf, offset, offset + len), ' ');
            throw new IllegalArgumentException(String.format("huffman decode string fail, hex %s", hex), throwable);
        }
    }

    private String createString(byte[] buf, int offset, int len, boolean isName) {
        if (validate) {
            validateBytes(buf, offset, len);
        }
        return isName ? HttpUnsafe.createAsciiString(buf, offset, len) : new String(buf, offset, len, charset);
    }

    /**
     * Reject header field bytes containing CR, LF or NUL (RFC 7540 §8.1.2.6).
     */
    private static void validateBytes(byte[] buf, int offset, int len) {
        for (int i = offset, end = offset + len; i < end; ++i) {
            byte b = buf[i];
            if( b > '\r') continue; // fast path: most bytes are printable ASCII, skip check
            if (b == 0 || b == '\r' || b == '\n') {
                throw new Http2HpackException("Invalid control character in header field (RFC 7540 §8.1.2.6)");
            }
        }
    }

    private byte[] allocateDeHuffmanOutput(int len) {
        byte[] buf = huffmanDeOutputCache;
        if (len <= buf.length) return buf;
        buf = Arrays.copyOf(buf, len);
        if (len < 1024) {
            huffmanDeOutputCache = buf;
        }
        return buf;
    }
}

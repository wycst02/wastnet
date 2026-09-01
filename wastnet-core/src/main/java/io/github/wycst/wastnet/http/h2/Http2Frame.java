package io.github.wycst.wastnet.http.h2;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.github.wycst.wastnet.util.Utils;

/**
 * HTTP/2 frame structure.
 * <p>
 * Immutable value object: all fields are final and set only via the constructor.
 * Frames are short-lived objects: each represents one parsed/written frame, used
 * once within a single read/write cycle and then discarded, so no long-term
 * reference is held and no cross-thread sharing occurs.
 *
 * @author wangyc
 */
public final class Http2Frame {

    // Frame type codes
    public static final byte FRAME_TYPE_DATA = 0x00;
    public static final byte FRAME_TYPE_HEADERS = 0x01;
    public static final byte FRAME_TYPE_PRIORITY = 0x02;
    public static final byte FRAME_TYPE_RST_STREAM = 0x03;
    public static final byte FRAME_TYPE_SETTINGS = 0x04;
    public static final byte FRAME_TYPE_PUSH_PROMISE = 0x05;
    public static final byte FRAME_TYPE_PING = 0x06;
    public static final byte FRAME_TYPE_GOAWAY = 0x07;
    public static final byte FRAME_TYPE_WINDOW_UPDATE = 0x08;
    public static final byte FRAME_TYPE_CONTINUATION = 0x09;

    // Frame flags (bit positions vary by frame type — see RFC 7540)
    /**
     * END_STREAM (bit 0), {@code 0x01}.
     * <p>
     * Used by: {@code DATA}, {@code HEADERS}.
     * Indicates that the frame is the last in the stream.
     */
    public static final int END_STREAM = 0x01;

    /**
     * END_HEADERS (bit 2), {@code 0x04}.
     * <p>
     * Used by: {@code HEADERS}, {@code CONTINUATION}.
     * Indicates that the header block fragment is the last.
     */
    public static final int END_HEADERS = 0x04;

    /**
     * PADDED (bit 3), {@code 0x08}.
     * <p>
     * Used by: {@code DATA}, {@code HEADERS}, {@code PUSH_PROMISE}.
     * Indicates that the frame is padded.
     */
    public static final int PADDED = 0x08;

    /**
     * PRIORITY (bit 5), {@code 0x20}.
     * <p>
     * Used by: {@code HEADERS}.
     * Indicates the presence of the stream dependency / weight field.
     */
    public static final int PRIORITY = 0x20;

    /**
     * PING ACK (bit 0), {@code 0x01}.
     * <p>
     * Used by: {@code PING} (RFC 7540 §6.7).
     * The PING response MUST have this flag set.
     * Note: the h2 ACK flag is always bit 0 (0x01), shared by PING and SETTINGS.
     */
    public static final int PING_ACK = 0x01;

    /**
     * SETTINGS ACK (bit 0), {@code 0x01}.
     * <p>
     * Used by: {@code SETTINGS} (RFC 7540 §6.5).
     * When set, the payload MUST be empty.
     */
    public static final int SETTINGS_ACK = 0x01;

    /**
     * Complete frame data (9-byte header + payload)
     */
    public final byte[] frameData;

    /**
     * Start offset of this frame within {@link #frameData}.
     * Allows multiple frames to share one buffer as slices.
     */
    public final int frameOffset;

    /**
     * Total frame length (9-byte header + payload), within {@link #frameData}.
     */
    public final int frameLength;

    /**
     * 24-bit unsigned integer, payload length
     */
    public final int payloadLength;

    /**
     * Frame type, 8 bits
     */
    public final Http2FrameType type;

    /**
     * Frame flags, 8 bits
     */
    public int flags;

    /**
     * Stream identifier, 31-bit unsigned integer
     */
    public final int streamId;

    /**
     * Offset of the payload within {@link #frameData} (frame origin + 9-byte header).
     */
    public final int payloadOffset;

    /**
     * Actual payload offset within {@code frameData} (after PADDED/PRIORITY overhead)
     */
    public final int payloadActualOffset;

    /**
     * Actual payload length (excluding trailing padding)
     */
    public final int payloadActualLength;

    /**
     * Full-arg constructor; immutable after construction.
     *
     * @param frameData     complete frame data (9-byte header + payload)
     * @param frameOffset   offset of this frame within {@code frameData}
     * @param payloadOffset actual payload offset relative to frame origin
     * @param payloadLen    actual payload length (excluding trailing padding)
     * @param length        declared payload length (24-bit)
     * @param type          frame type
     * @param flags         frame flags
     * @param streamId      stream identifier (31-bit)
     */
    public Http2Frame(byte[] frameData, int frameOffset, int payloadOffset, int payloadLen,
                      int length, Http2FrameType type, int flags, int streamId) {
        this.frameData = frameData;
        this.frameOffset = frameOffset;
        this.frameLength = length + 9;
        this.payloadOffset = frameOffset + 9;
        this.payloadActualOffset = frameOffset + payloadOffset;
        this.payloadActualLength = payloadLen;
        this.payloadLength = length;
        this.type = type;
        this.flags = flags;
        this.streamId = streamId;
    }

    /**
     * Dump frame data in a human-readable hex format.
     *
     * @return hex dump string
     */
    public String toHexDump() {
        byte[] data = frameData;
        StringBuilder sb = new StringBuilder();

        // Leading newline for separation
        sb.append("\n");

        String typeName = type != null ? type.name() : "UNKNOWN";
        String headerInfo = String.format("[%s] streamId=%d length=%d flags=0x%02x", typeName, streamId, payloadLength, flags);

        // Build hex dump rows
        int bytesPerLine = 16;
        int totalLength = frameOffset + frameLength;

        // Calculate hex dump header and row widths
        // Format: " Offset  | 00 01 02 03 04 05 06 07  08 09 0a 0b 0c 0d 0e 0f | ASCII"
        // Data row: offset(8) + " | "(3) + hex bytes(49) + "| "(2) + ascii(16) = 78
        String hexDumpHeader = " Offset  | 00 01 02 03 04 05 06 07  08 09 0a 0b 0c 0d 0e 0f | ASCII";

        // Determine box width based on Frame length
        // Calculate hex dump row width: offset(8) + " | "(3) + hex(16*3+1=49) + "| "(2) + ascii(16) = 78
        int hexDumpRowWidth = 8 + 3 + 49 + 2 + 16;
        int frameWidth = 10 + totalLength * 2;  // "Frame:   " + hex bytes
        int width = Math.max(headerInfo.length(), frameWidth);
        width = Math.max(width, hexDumpRowWidth);
        width = width + 4; // "| " + " |" = 4

        // Separator: contentWidth = width - 4
        String hexDumpSep = repeat("-", width - 4);

        // Build output
        String border = "+" + repeat("-", width - 2) + "+";
        String innerPrefix = "| ";
        String innerSuffix = " |";

        sb.append(border).append("\n");

        // Info line
        int contentWidth = width - 4;
        sb.append(innerPrefix);
        sb.append(String.format("%-" + contentWidth + "s", headerInfo));
        sb.append(innerSuffix).append("\n");

        sb.append(border).append("\n");

        // Frame hex
        sb.append(innerPrefix);
        String frameHex = "Frame:   " + Utils.toHexStringLower(data, frameOffset, frameLength);
        sb.append(String.format("%-" + contentWidth + "s", frameHex));
        sb.append(innerSuffix).append("\n");

        // Head hex
        sb.append(innerPrefix);
        String headHex = "Head:    " + Utils.toHexStringLower(data, frameOffset, 9);
        sb.append(String.format("%-" + contentWidth + "s", headHex));
        sb.append(innerSuffix).append("\n");

        // Payload hex
        if (payloadLength > 0) {
            sb.append(innerPrefix);
            sb.append(String.format("%-" + contentWidth + "s", "Payload: " + Utils.toHexStringLower(data, frameOffset + 9, payloadLength)));
            sb.append(innerSuffix).append("\n");
        }

        // Separator
        sb.append(border).append("\n");

        // Hex dump section
        sb.append(innerPrefix);
        // Pad or truncate to contentWidth
        String hdHeader = hexDumpHeader;
        hdHeader = hdHeader + repeat(" ", contentWidth - hdHeader.length());
        sb.append(hdHeader);
        sb.append(innerSuffix).append("\n");

        sb.append(innerPrefix);
        sb.append(hexDumpSep);
        sb.append(innerSuffix).append("\n");

        // Data rows
        for (int i = frameOffset; i < totalLength; i += bytesPerLine) {
            // Build the line content first, then pad to contentWidth
            sb.append(innerPrefix);
            StringBuilder line = new StringBuilder();

            // Offset (8 hex digits)
            line.append(String.format("%08x", i));
            line.append(" | ");

            // Hex bytes (16 bytes per line)
            for (int j = 0; j < bytesPerLine; j++) {
                if (j == 8) line.append(" ");
                int index = i + j;
                if (index < totalLength) {
                    int b = data[index] & 0xFF;
                    line.append(String.format("%02x ", b));
                } else {
                    line.append("   ");
                }
            }
            line.append("| ");

            // ASCII (16 chars)
            for (int j = 0; j < bytesPerLine; j++) {
                int index = i + j;
                if (index < totalLength) {
                    int b = data[index] & 0xFF;
                    if (b >= 32 && b <= 126) {
                        line.append((char) b);
                    } else {
                        line.append('.');
                    }
                } else {
                    line.append(' ');
                }
            }

            // Pad line to contentWidth
            String lineStr = String.format("%-" + contentWidth + "s", line.toString());
            sb.append(lineStr);
            sb.append(innerSuffix).append("\n");
        }

        sb.append(border).append("\n");

        return sb.toString();
    }

    private static String repeat(String s, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; ++i) {
            sb.append(s);
        }
        return sb.toString();
    }

    /**
     * Dump byte array in a human-readable hex format.
     *
     * @param data the byte array to dump
     * @return hex dump string
     */
    public static String toHexDump(byte[] data) {
        return toHexDump(data, 16);
    }

    /**
     * Dump byte array with specified bytes per line.
     *
     * @param data         the byte array to dump
     * @param bytesPerLine number of bytes per line
     * @return hex dump string
     */
    public static String toHexDump(byte[] data, int bytesPerLine) {
        if (data == null || data.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int totalLength = data.length;

        // Column headers
        int offsetWidth = Math.max(4, Integer.toHexString(totalLength).length());
        StringBuilder headerOffset = new StringBuilder("Offset");
        StringBuilder headerHex = new StringBuilder();
        StringBuilder headerAscii = new StringBuilder("ASCII");
        for (int i = 0; i < bytesPerLine; ++i) {
            if (i == bytesPerLine / 2) {
                headerHex.append("  ");
            }
            headerHex.append(String.format("%02x ", i));
        }
        headerOffset.append(String.format("%" + (offsetWidth - 6) + "s", ""));
        headerAscii.insert(0, String.format("%" + (offsetWidth + 3 + bytesPerLine * 3 + 2) + "s", ""));

        sb.append(headerOffset).append(" | ").append(headerHex).append("| ").append(headerAscii).append("\n");

        // Separator
        String sep = "-";
        String lineSep = String.format("%" + (offsetWidth + 2) + "s", sep).replace(' ', '-')
                + "-+-" + String.format("%-" + (bytesPerLine * 3 + bytesPerLine / 2) + "s", sep).replace(' ', '-')
                + "-+-" + String.format("%" + bytesPerLine + "s", sep).replace(' ', '-');
        sb.append(lineSep).append("\n");

        // Data
        for (int i = 0; i < totalLength; i += bytesPerLine) {
            // Offset
            sb.append(String.format("%0" + offsetWidth + "x", i));
            sb.append(" | ");

            // Hex bytes
            StringBuilder hexPart = new StringBuilder();
            StringBuilder asciiPart = new StringBuilder();
            for (int j = 0; j < bytesPerLine; j++) {
                int index = i + j;
                if (index < totalLength) {
                    int b = data[index] & 0xFF;
                    if (j == bytesPerLine / 2) {
                        hexPart.append(" ");
                    }
                    hexPart.append(String.format("%02x ", b));
                    // ASCII
                    if (b >= 32 && b <= 126) {
                        asciiPart.append((char) b);
                    } else {
                        asciiPart.append('.');
                    }
                } else {
                    if (j == bytesPerLine / 2) {
                        hexPart.append(" ");
                    }
                    hexPart.append("   ");
                }
            }
            sb.append(String.format("%-" + (bytesPerLine * 3 + bytesPerLine / 2) + "s", hexPart.toString()));
            sb.append("| ");
            sb.append(asciiPart);
            sb.append("\n");
        }
        sb.append(lineSep);

        return sb.toString();
    }



    /**
     * Set the frame's 8-bit flags byte (frame-header offset 4).
     * <p>
     * Mutates the backing {@code frameData} array and keeps the {@link #flags}
     * field in sync, so {@link #hasFlags(int)} and {@link #toHexDump()} reflect
     * the change as well.
     *
     * @param flags new flags value (low 8 bits used)
     */
    void setFlags(int flags) {
        this.flags = flags;
        frameData[frameOffset + 4] = (byte) flags;
    }

    /**
     * Parses all frames in the buffer (supports a merged multi-frame write) and returns them in order.
     * <p>
     * Internal call only: the frame data is framework-produced and always trusted (well-formed, no partial frame).
     *
     * @param buf array-backed ByteBuffer, positioned at 0, limit = sum of all frame sizes
     * @return frames in order
     */
    static List<Http2Frame> fromByteBuffer(ByteBuffer buf) {
        List<Http2Frame> frames = new ArrayList<>();
        byte[] src = buf.array();
        int total = buf.limit();
        int pos = 0;
        while (pos + 9 <= total) {
            int len = ((src[pos] & 0xff) << 16) | ((src[pos + 1] & 0xff) << 8) | (src[pos + 2] & 0xff);
            int end = pos + 9 + len;
            if (end > total) break;
            byte[] data = new byte[9 + len];
            System.arraycopy(src, pos, data, 0, data.length);
            Http2Frame frame = new Http2Frame(
                    data,
                    0,
                    9,
                    len,
                    len,
                    Http2FrameType.valueOf(data[3] & 0xff),
                    data[4] & 0xff,
                    ((data[5] & 0xff) << 24) | ((data[6] & 0xff) << 16) | ((data[7] & 0xff) << 8) | (data[8] & 0xff));
            frames.add(frame);
            pos = end;
        }
        return frames;
    }

    /**
     * Check if a specific flag is set.
     *
     * @param flag flag bit mask
     * @return true if the flag is set
     */
    public boolean hasFlag(int flag) {
        return (flags & flag) != 0;
    }

    /**
     * Check if all specified flags are set.
     *
     * @param flags flag bit mask
     * @return true if all flags are set
     */
    public boolean hasFlags(int flags) {
        return (this.flags & flags) == flags;
    }

    /**
     * Check if the END_STREAM flag is set.
     *
     * @return true if the END_STREAM flag is set
     */
    public boolean isEndStream() {
        return hasFlag(END_STREAM);
    }

    /**
     * Check if the END_HEADERS flag is set.
     *
     * @return true if the END_HEADERS flag is set
     */
    public boolean isEndHeaders() {
        return hasFlag(END_HEADERS);
    }

    /**
     * Get the actual payload data (excluding frame overhead).
     *
     * @return payload data
     */
    public byte[] actualPayload() {
        return Arrays.copyOfRange(frameData, payloadActualOffset, payloadActualOffset + payloadActualLength);
    }

    /**
     * Wrap this frame as a ByteBuffer for writing.
     *
     * @return ByteBuffer view over {@code frameData} from {@code frameOffset}, length = header + payload
     */
    public ByteBuffer toByteBuffer() {
        return ByteBuffer.wrap(frameData, frameOffset, frameLength);
    }
}

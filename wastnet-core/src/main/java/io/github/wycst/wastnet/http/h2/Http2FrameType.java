package io.github.wycst.wastnet.http.h2;

/**
 * HTTP/2 frame type (RFC 7540 §4, §6).
 * <p>
 * Each constant maps to its 8-bit wire type code defined in the HTTP/2
 * frame header. The ordinal order must match the wire code values so that
 * {@link #valueOf(int)} can use ordinal-based lookup.
 *
 * @since 2024-2-26
 * @author wangyc
 */
public enum Http2FrameType {

    /** DATA frame (RFC 7540 §6.1): carries arbitrary, variable-length sequences of octets. */
    DATA(Http2Frame.FRAME_TYPE_DATA),
    /** HEADERS frame (RFC 7540 §6.2): opens or resumes a stream, carries header block fragments. */
    HEADERS(Http2Frame.FRAME_TYPE_HEADERS),
    /** PRIORITY frame (RFC 7540 §6.3): specifies the sender-advised priority of a stream. */
    PRIORITY(Http2Frame.FRAME_TYPE_PRIORITY),
    /** RST_STREAM frame (RFC 7540 §6.4): abruptly terminates a stream. */
    RST_STREAM(Http2Frame.FRAME_TYPE_RST_STREAM),
    /** SETTINGS frame (RFC 7540 §6.5): conveys configuration parameters affecting peer communication. */
    SETTINGS(Http2Frame.FRAME_TYPE_SETTINGS),
    /** PUSH_PROMISE frame (RFC 7540 §6.6): server-initiated push, notifies the client of an upcoming stream. */
    PUSH_PROMISE(Http2Frame.FRAME_TYPE_PUSH_PROMISE),
    /** PING frame (RFC 7540 §6.7): mechanism for measuring minimal round-trip time / heartbeats. */
    PING(Http2Frame.FRAME_TYPE_PING),
    /** GOAWAY frame (RFC 7540 §6.8): initiates graceful shutdown of a connection. */
    GOAWAY(Http2Frame.FRAME_TYPE_GOAWAY),
    /** WINDOW_UPDATE frame (RFC 7540 §6.9): implements flow control, increments window size. */
    WINDOW_UPDATE(Http2Frame.FRAME_TYPE_WINDOW_UPDATE),
    /** CONTINUATION frame (RFC 7540 §6.10): continues a header block fragment sequence. */
    CONTINUATION(Http2Frame.FRAME_TYPE_CONTINUATION);

    public final byte code;

    Http2FrameType(int code) {
        this.code = (byte) code;
    }

    /**
     * Look up a frame type by its 8-bit wire code.
     * <p>
     * Uses ordinal-based O(1) lookup (enum ordinals must match wire codes).
     * Unknown frame types return {@code null} per RFC 7540 §9 (ignore).
     *
     * @param code the 8-bit frame type code from the frame header
     * @return the matching {@link Http2FrameType}, or {@code null} for unknown codes
     */
    public static Http2FrameType valueOf(int code) {
        Http2FrameType[] values = Http2FrameType.values();
        if (code < 0 || code >= values.length) {
            return null;
        }
        return values[code];
    }
}

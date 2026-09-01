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
import io.github.wycst.wastnet.http.HttpHeaderValues;
import io.github.wycst.wastnet.http.HttpMessage;
import io.github.wycst.wastnet.http.HttpOptions;
import io.github.wycst.wastnet.http.reader.HttpMessageReader;
import io.github.wycst.wastnet.log.Log;
import io.github.wycst.wastnet.log.LogFactory;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import io.github.wycst.wastnet.socket.tcp.ChannelSSLContext;
import io.github.wycst.wastnet.util.Utils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <p> HTTP/2 frame reader base class (shared by server and client). </p>
 * <p> Provides byte-level frame parsing, window management, and common frame sending. </p>
 *
 * @author wangyc
 */
public abstract class Http2MessageReader extends HttpMessageReader<HttpMessage> {

    /**
     * Debug switch (-Dwastnet.http2.debug=true);
     * needs logger level DEBUG (LogFactory.setLevel or -Dwastnet.log.level=DEBUG, default INFO).
     */
    static final boolean DEBUG = Boolean.getBoolean("wastnet.http2.debug");
    // Logger
    final Log LOG = LogFactory.getLog(getClass());

    /** HTTP/2 connection preface length (RFC 7540 §3.5) */
    static final int PREFACE_MAGIC_LEN = 24;

    /**
     * Client connection preface magic bytes (RFC 7540 §3.5, RFC 9113 §3.5).
     * <p>
     * ASCII representation:
     * {@code PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n}
     */
    static final byte[] CLIENT_CONNECTION_PREFACE = {
            0x50, 0x52, 0x49, 0x20, 0x2A, 0x20, 0x48, 0x54, 0x54, 0x50, 0x2F, 0x32,
            0x2E, 0x30, 0x0D, 0x0A, 0x0D, 0x0A, 0x53, 0x4D, 0x0D, 0x0A, 0x0D, 0x0A
    };

    // Pre-cached 3 little-endian longs for O(1) preface validation
    private static final long PREFACE_LONG_0;
    private static final long PREFACE_LONG_1;
    private static final long PREFACE_LONG_2;

    static {
        ByteBuffer buf = ByteBuffer.wrap(CLIENT_CONNECTION_PREFACE).order(ByteOrder.LITTLE_ENDIAN);
        PREFACE_LONG_0 = buf.getLong();
        PREFACE_LONG_1 = buf.getLong();
        PREFACE_LONG_2 = buf.getLong();
    }

    /**
     * Default DATA frame payload size (RFC 7540 §6.5.2)
     */
    static final int MAX_DATA_PAYLOAD_SIZE = 16384;

    // Default initial send window (65535), used before remote SETTINGS arrives
    static final int INITIAL_SEND_WINDOW_SIZE = 0xFFFF;
    final int initialReceiveWindowSize; // default 65535, overridable via HttpOptions.HTTP2_INITIAL_SEND_WINDOW_SIZE

    /**
     * Connection-level window size (initialReceiveWindowSize * 16 = 1MB)
     */
    final int initConnectReceiveWindowSize;

    /**
     * Maximum bodyData capacity before switching to streaming mode.
     * Equals max(initialReceiveWindowSize * 2, MAX_BODY_IN_MEMORY).
     */
    final int maxStreamCapacitySize;

    /** Enter streaming at first window exhaustion when {@link HttpConf#HTTP2_STREAM_EARLY} is set. */
    final boolean streamEarly;

    /** Max allowed header-block size (initial + trailer), overridable via HttpOptions.MAX_HTTP_HEADER_SIZE. */
    final int maxHttpHeaderSize;

    /** Max time (ms) a sender blocks waiting for flow-control credit, overridable via HttpOptions.HTTP2_FLOW_CONTROL_WAIT_TIMEOUT_MS. */
    final int flowControlWaitTimeoutMs;

    // SETTINGS ACK (9 bytes) shared by both sides
    static final byte[] SETTINGS_ACK = {0, 0, 0, 4, 1, 0, 0, 0, 0};

    /**
     * Connection-level send window. Initialized to the RFC 7540 §6.9.2
     * default (65535), changed only by the remote peer's SETTINGS and
     * WINDOW_UPDATE frames.
     */
    long connectSendWindow = INITIAL_SEND_WINDOW_SIZE;

    /**
     * Remote-advertised initial stream window size, starts at default 65535.
     * Updated when remote sends SETTINGS with SETTINGS_INITIAL_WINDOW_SIZE.
     */
    int streamInitSendWindowSize = INITIAL_SEND_WINDOW_SIZE;

    /**
     * Max local HPACK encoder dynamic table size advertised by the remote peer
     * via SETTINGS_HEADER_TABLE_SIZE. Default 4096 per RFC 7541 §4.1.
     * The local HPACK encoder MUST NOT use a dynamic table larger than this
     * value when encoding headers.
     */
    int maxHpackEncoderTableSize = 4096;

    /**
     * Pre-defined content-type values eligible for HPACK dynamic-table indexing.
     */
    private static final String[] INDEXED_HEADER_VALUES = {
            HttpHeaderValues.TEXT_HTML, HttpHeaderValues.TEXT_HTML_UTF8, 
            HttpHeaderValues.APPLICATION_JSON, HttpHeaderValues.APPLICATION_JSON_UTF8,
            HttpHeaderValues.APPLICATION_OCTET_STREAM, 
            HttpHeaderValues.TEXT_PLAIN, HttpHeaderValues.TEXT_PLAIN_UTF8,
            "text/css", "image/png", "image/jpeg", "image/gif", "image/webp",
            "application/javascript", 
            HttpHeaderValues.FONT_WOFF2, HttpHeaderValues.IMAGE_SVG_XML
    };

    /** Per-connection dynamic-table indices for {@link #INDEXED_HEADER_VALUES}; 0=unseeded, -1=claimed pending, >0=index. */
    final AtomicIntegerArray headerIndex = new AtomicIntegerArray(INDEXED_HEADER_VALUES.length);
    /** Set when the peer shrinks HEADER_TABLE_SIZE or the table is too small. */
    volatile boolean headerIndexDisabled;

    /**
     * Remote-advertised max concurrent streams.
     * For server: client limits server push streams.
     * For client: server limits client-initiated streams.
     */
    int remoteMaxConcurrentStreams;

    /**
     * Max frame payload size for sending, advertised by the remote peer via SETTINGS_MAX_FRAME_SIZE.
     * Default 16384 per RFC 7540 §6.5.2.
     */
    int maxSendPayloadSize = MAX_DATA_PAYLOAD_SIZE;

    final Http2HpackCodec http2HpackCodec = new Http2HpackCodec();

    volatile boolean valid = true;

    /** Channel context reference, set at handshake (init); used only for monitoring snapshots. */
    transient final ChannelContext ctx;

    // ==================== diagnostic state (monitoring only, no logic impact) ====================
    /** Connection creation timestamp (set at init), used to compute connection age on demand. */
    volatile long createdAt;
    volatile String lastCloseReason;

    final AtomicBoolean flushPending = new AtomicBoolean();

    int currentMaxStreamId;
    int nextFrameLength;
    /**
     * Connection-level receive window
     */
    final AtomicLong connectRecvWindow;
    final Map<Integer, Http2Stream> streamMap = new ConcurrentHashMap<>();

    public Http2MessageReader() {
        this(ChannelContext.EMPTY_CONTEXT);
    }

    public Http2MessageReader(ChannelContext ctx) {
        this.ctx = ctx;
        this.initialReceiveWindowSize = ctx.option(HttpOptions.HTTP2_INITIAL_SEND_WINDOW_SIZE);
        this.initConnectReceiveWindowSize = initialReceiveWindowSize << 4;
        this.connectRecvWindow = new AtomicLong(initConnectReceiveWindowSize);
        this.maxStreamCapacitySize = Math.max(initialReceiveWindowSize << 1, ctx.option(HttpOptions.MAX_BODY_IN_MEMORY));
        this.streamEarly = ctx.option(HttpOptions.HTTP2_STREAM_EARLY);
        this.maxHttpHeaderSize = ctx.option(HttpOptions.MAX_HTTP_HEADER_SIZE);
        this.flowControlWaitTimeoutMs = ctx.option(HttpOptions.HTTP2_FLOW_CONTROL_WAIT_TIMEOUT_MS);
    }

    // ==================== Big-endian byte reading utilities ====================

    /**
     * Read 3-byte big-endian unsigned int (used for frame length)
     */
    static int readUInt24(byte[] buf, int off) {
        return ((buf[off] & 0xFF) << 16) | ((buf[off + 1] & 0xFF) << 8) | (buf[off + 2] & 0xFF);
    }

    /**
     * Read 4-byte big-endian int (used for streamId, settings values)
     */
    static int readInt32(byte[] buf, int off) {
        return ((buf[off] & 0xFF) << 24) | ((buf[off + 1] & 0xFF) << 16)
                | ((buf[off + 2] & 0xFF) << 8) | (buf[off + 3] & 0xFF);
    }

    /**
     * Read 2-byte big-endian unsigned int (used for settings ids)
     */
    static int readUInt16(byte[] buf, int off) {
        return ((buf[off] & 0xFF) << 8) | (buf[off + 1] & 0xFF);
    }

    // ==================== Abstract lifecycle ====================

    /**
     * Initialize the H2 session (preface exchange, settings).
     * Server: wait for client preface.
     * Client: send client preface + settings.
     */
    @Override
    public abstract void init(ChannelContext ctx) throws Exception;

    // ==================== Frame reading ====================
    /**
     * Read and parse HTTP/2 frame from buffer.
     * <p>
     * Frame structure: 9-byte header + payload
     */
    private Http2Frame readNextMessageFrame(ChannelContext ctx, final byte[] buf, final int off, final int len) throws IOException {
        int length = readUInt24(buf, off);
        int type = buf[off + 3];
        int flags = buf[off + 4];
        int streamId = readInt32(buf, off + 5);
        if (streamId < 0) {
            closeConnection(ctx, 1); // PROTOCOL_ERROR (RFC 7540 §5.1.1: reserved stream id bit set)
            return null;
        }
        final Http2FrameType frameType = Http2FrameType.valueOf(type);
        
        // Max receivable frame size: server does not send SETTINGS_MAX_FRAME_SIZE updates,
        // so the default 16384 (RFC 7540 §6.5.2) is the hard limit for inbound frames.
        if (length > MAX_DATA_PAYLOAD_SIZE) {
            closeConnection(ctx, 6); // FRAME_SIZE_ERROR (RFC 7540 §6.5.2)
            return null;
        }
        final int frameLength = 9 + length;
        nextFrameLength = frameLength;
        byte[] frameData = buf;
        int frameOffset = off;
        if (len < frameLength) {
            frameData = new byte[frameLength];
            System.arraycopy(buf, off, frameData, 0, len);
            readInternal(ctx, frameData, len, frameLength - len);
            frameOffset = 0;
        } // else: frame fully inside read buffer, reference shared array directly (zero-copy).

        if (frameType == null) return null; // unknown frame type (RFC 7540 §4.1): ignore and discard

        int payloadOffset = 9, payloadLength = length;
        if (frameType == Http2FrameType.HEADERS && (flags & Http2Frame.PRIORITY) == Http2Frame.PRIORITY) {
            payloadOffset += 5;
            payloadLength -= 5;
        }
        // Only DATA (0x0) and HEADERS (0x1) are padded here; PUSH_PROMISE (0x5) is unsupported (ENABLE_PUSH=0), CONTINUATION (0x9) has no padding per RFC 7540.
        if (type <= Http2Frame.FRAME_TYPE_HEADERS && (flags & Http2Frame.PADDED) == Http2Frame.PADDED) {
            payloadOffset += 1;
            payloadLength -= 1 + (frameData[frameOffset + 9] & 0xFF); // RFC 7540 §6.2: PADDED frames carry 1-byte Pad Length at byte[9] plus Pad Length bytes of padding
        }
        if(payloadLength < 0) {
            closeConnection(ctx, 6); // FRAME_SIZE_ERROR (RFC 7540 §6.2: padding/overhead exceeds declared length)
            return null;
        }
        return new Http2Frame(frameData, frameOffset, payloadOffset, payloadLength, length, frameType, flags, streamId);
    }

    // ==================== Main decode loop ====================

    /**
     * Decode and process HTTP/2 frames from the channel.
     */
    @Override
    public void decode(ChannelContext ctx, byte[] buf, int offset, int len) {
        while (valid) {
            try {
                if (len < 9) {
                    buf = read(ctx, buf, offset + len, 9 - len);
                    len = 9;
                }
                Http2Frame frame = readNextMessageFrame(ctx, buf, offset, len);
                if (frame != null) {
                    handleFrame(ctx, frame);
                }
                offset += nextFrameLength;
                len -= nextFrameLength;
                if (len < 1) return;
            } catch (Throwable e) {
                if(DEBUG) {
                    LOG.error("Http2MessageReader decode error, hexBytes: " + Utils.printHexString(Arrays.copyOfRange(buf, offset, offset + len), ' '), e);
                }
                closeConnection(ctx, 1); // PROTOCOL_ERROR (fallback: unknown frame type / decode failure)
                return;
            }
        }
    }
    // ==================== Frame dispatch ====================
    /**
     * Log prefix for inbound frames, indicating the peer that sent them.
     * Subclasses override with "Client " or "Server ".
     */
    protected String debugPeerPrefix() {
        return "Client ";
    }

    /**
     * Handle HTTP/2 frame based on frame type.
     */
    final void handleFrame(ChannelContext ctx, Http2Frame frame) throws IOException {
        if (DEBUG) {
            synchronized (this) {
                if (frame.type != Http2FrameType.DATA) {
                    LOG.debug("[{} {}] streamId={} length={}\n{}",
                            debugPeerPrefix(), frame.type, frame.streamId, frame.payloadLength, frame.toHexDump());
                } else {
                    LOG.debug("[{} {}] streamId={} length={}",
                            debugPeerPrefix(), frame.type, frame.streamId, frame.payloadLength);
                }
            }
        }
        final int streamId = frame.streamId;
        if (streamId == 0) {
            handleControlFrame(ctx, frame);
            return;
        }
        Http2Stream stream = getStream(streamId, ctx);
        if (stream == null) {
            // Stream is absent from the active map (closed or never opened). Per RFC 7540 §5.1
            // such frames must be ignored; handleOrphanStreamFrame accounts flow-control credit.
            handleOrphanStreamFrame(ctx, frame);
            return;
        }
        stream.handleFrame(frame, ctx);
    }

    /**
     * Handle a frame whose stream is absent from the active map (closed or never opened).
     * RFC 7540 §5.1 requires such frames to be ignored. A trailing DATA frame still consumes
     * connection-level flow-control credit (§6.9.1), so we account it and return a connection
     * WINDOW_UPDATE to avoid leaking the shared window. We deliberately do NOT send a stream
     * WINDOW_UPDATE: the stream is gone and compliant clients must ignore it anyway; clients
     * that keep sending after RST are misbehaving and only block themselves.
     */
    private void handleOrphanStreamFrame(ChannelContext ctx, Http2Frame frame) throws IOException {
        // Only DATA needs flow-control accounting; any other frame on a closed stream is ignored.
        if (frame.type != Http2FrameType.DATA) {
            return;
        }
        int len = frame.payloadActualLength;
        if (connectRecvWindow.addAndGet(-len) < 0) {
            closeConnection(ctx, 3); // FLOW_CONTROL_ERROR (RFC 7540 §6.9.1)
            return;
        }
        sendConnectionWindowUpdate(ctx, len, true); // restores connectRecvWindow (+len) -> net 0
    }

    /**
     * Invoked when an inbound (client-sent) RST_STREAM frame targeting a stream is
     * received. Default no-op; {@code Http2ServerReader} overrides it to enforce the
     * per-connection Rapid Reset rate limit. Server-initiated RST (via
     * {@link #sendRstStreamFrame}) does NOT trigger this hook.
     *
     * @param ctx the channel context
     */
    protected void onInboundRstStream(ChannelContext ctx) {
    }

    // ==================== Control frame handling ====================

    /**
     * Handle a control frame received on stream 0.
     */
    private void handleControlFrame(ChannelContext ctx, Http2Frame frame) throws IOException {
        switch (frame.type) {
            case SETTINGS:
                handleSettingsFrame(ctx, frame);
                return;
            case PING:
                if (frame.payloadLength != 8) {
                    closeConnection(ctx, 6); // FRAME_SIZE_ERROR (RFC 7540 §6.7)
                    return;
                }
                // RFC 7540 §6.7: only echo a PING with ACK unset; an ACK-set PING was not originated here, ignore it.
                if ((frame.flags & Http2Frame.PING_ACK) == 0) {
                    frame.setFlags(Http2Frame.PING_ACK);
                    ctx.writeFlush(frame.toByteBuffer());
                }
                return;
            case WINDOW_UPDATE: {
                if (frame.payloadLength != 4) {
                    closeConnection(ctx, 6); // FRAME_SIZE_ERROR (RFC 7540 §6.9)
                    return;
                }
                int increment = readInt32(frame.frameData, frame.payloadOffset);
                synchronized (this) {
                    if ((connectSendWindow += increment) > Integer.MAX_VALUE || increment <= 0) {
                        closeConnection(ctx, increment <= 0 ? 1 : 3); // 0 or reserved-bit set -> PROTOCOL_ERROR; overflow -> FLOW_CONTROL_ERROR
                    }
                }
                wakeupSendWU();
                return;
            }
            case GOAWAY:
                handleGoawayFrame(ctx, frame);
                return;
            default: // RFC 7540 §6.3/6.4/6.6: not allowed on stream 0
                closeConnection(ctx, 1); // PROTOCOL_ERROR
        }
    }

    /**
     * Handle a GOAWAY frame received on stream 0 (RFC 7540 §6.8).
     * <p>
     * GOAWAY is a graceful shutdown signal, NOT a protocol error. The client
     * sends it to stop opening new streams; by the time it arrives the client
     * has normally received the replies it needs, so we close gracefully with
     * NO_ERROR instead of treating the frame as a protocol violation.
     */
    private void handleGoawayFrame(ChannelContext ctx, Http2Frame frame) {
        valid = false; // stop the decode loop; peer initiated graceful shutdown
        wakeupSendWU(); // unblock senders blocked waiting on send window
        if (frame.payloadLength < 8) {
            closeConnection(ctx, 6); // FRAME_SIZE_ERROR (RFC 7540 §6.8: GOAWAY >= 8 bytes)
            return;
        }
        byte[] data = frame.frameData;
        int lastStreamId = readInt32(data, frame.payloadOffset) & 0x7FFFFFFF; // clear reserved high bit
        int errorCode = readInt32(data, frame.payloadOffset + 4);
        if (errorCode != 0) {
            LOG.warn("HTTP/2 peer sent GOAWAY with error code {} (last-stream-id={})", errorCode, lastStreamId);
        }
        ctx.close(); // graceful TCP close; no GOAWAY echo needed since peer initiated shutdown
    }

    /**
     * Parse and apply SETTINGS frame from the remote peer.
     */
    private void handleSettingsFrame(ChannelContext ctx, Http2Frame frame) {
        // Ignore SETTINGS ACK (length must be 0, RFC 7540 §6.5)
        if ((frame.flags & Http2Frame.SETTINGS_ACK) != 0) {
            if (frame.payloadLength != 0) {
                closeConnection(ctx, 6); // FRAME_SIZE_ERROR
            }
            return;
        }

        byte[] data = frame.frameData;
        int offset = frame.payloadOffset;
        int payloadLen = frame.payloadLength;

        try {
            if(payloadLen % 6 != 0) {
                closeConnection(ctx, 6); // FRAME_SIZE_ERROR
                return;
            }
            for (int i = 0; i < payloadLen; i += 6) {
                int id = readUInt16(data, offset + i);
                int value = readInt32(data, offset + i + 2);
                switch (id) {
                    case 1: // SETTINGS_HEADER_TABLE_SIZE
                        // RFC 7540 §6.5.2: value is unsigned 32-bit (0..2^32-1), no error bound.
                        // Restore unsigned from readInt32 and cap to int range before use.
                        long uint32 = value & 0xFFFFFFFFL;
                        if(uint32 < 2048) {
                            headerIndexDisabled = true; // table below our indexed size (~889B); drop indexing to avoid stale refs
                        }
                        maxHpackEncoderTableSize = (uint32 > Integer.MAX_VALUE) ? Integer.MAX_VALUE : (int) uint32;
                        break;
                    case 2: // SETTINGS_ENABLE_PUSH
                        // RFC 7540 §6.5.2: value must be 0/1, else PROTOCOL_ERROR
                        if (value >>> 1 != 0) { // value MUST be 0 or 1 (RFC 7540 §6.5.2)
                            closeConnection(ctx, 1); // PROTOCOL_ERROR
                            return;
                        }
                        break;
                    case 3: // SETTINGS_MAX_CONCURRENT_STREAMS
                        remoteMaxConcurrentStreams = value;
                        break;
                    case 4: // SETTINGS_INITIAL_WINDOW_SIZE
                        if (value < 0) {
                            closeConnection(ctx, 1); // PROTOCOL_ERROR
                            return;
                        }
                        // RFC 7540 §6.9.2: applies to all streams; adjust sendWindow by delta
                        if (value != streamInitSendWindowSize) {
                            int delta = value - streamInitSendWindowSize;
                            streamInitSendWindowSize = value;
                            for (Http2Stream stream : streamMap.values()) {
                                synchronized (this) {
                                    if (delta > 0 && stream.sendWindow > Integer.MAX_VALUE - delta) {
                                        closeConnection(ctx, 3); // FLOW_CONTROL_ERROR (RFC 7540 §6.9.2)
                                        return;
                                    }
                                    stream.sendWindow += delta;
                                }
                            }
                            if (delta > 0) {
                                wakeupSendWU(); // window grew: wake senders blocked by insufficient window
                            }
                        }
                        break;
                    case 5: // SETTINGS_MAX_FRAME_SIZE
                        if (value < MAX_DATA_PAYLOAD_SIZE || value > 16777215) {
                            closeConnection(ctx, 1); // PROTOCOL_ERROR
                            return;
                        }
                        maxSendPayloadSize = value;
                        break;
                    default: break; // RFC 7540 §6.5.2: unknown id ignored
                }
            }

            // Send SETTINGS ACK
            ctx.writeFlush(ByteBuffer.wrap(SETTINGS_ACK));
        } catch (Exception e) {
            closeConnection(ctx, 1); // PROTOCOL_ERROR
        }
    }

    // ==================== Frame sending ====================

    void sendConnectionWindowUpdate(ChannelContext ctx, int windowLen) throws IOException {
        sendConnectionWindowUpdate(ctx, windowLen, false);
    }

    /**
     * Send a connection-level WINDOW_UPDATE.
     * When {@code flush} is true the frame is written and pushed out immediately
     * (client is blocked waiting for window credit to send more of this request's
     * body); otherwise it is buffered via {@link ChannelContext#writeSync} and
     * carried out later by the response flush.
     */
    void sendConnectionWindowUpdate(ChannelContext ctx, int windowLen, boolean flush) throws IOException {
        ByteBuffer frame = ByteBuffer.allocate(13);
        frame.put(2, (byte) 4);                                    // payload length
        frame.put(3, Http2Frame.FRAME_TYPE_WINDOW_UPDATE);          // type
        frame.putInt(9, windowLen).clear();                         // window size increment
        if (flush) ctx.writeFlush(frame); else ctx.writeSync(frame);
        connectRecvWindow.addAndGet(windowLen);
    }

    /**
     * Batch send stream + connection WINDOW_UPDATE frames in one TCP segment.
     * When {@code flush} is true the pair is written and pushed out immediately
     * (client is blocked waiting for window credit); otherwise buffered via
     * {@link ChannelContext#writeSync} for a later flush.
     */
    void sendWindowUpdatePair(ChannelContext ctx, Http2Stream streamCtx, int windowLen, boolean flush) throws IOException {
        ByteBuffer connWu = ByteBuffer.allocate(13);
        connWu.put(2, (byte) 4);                                    // payload length
        connWu.put(3, Http2Frame.FRAME_TYPE_WINDOW_UPDATE);          // type
        connWu.putInt(9, windowLen);

        ByteBuffer pair = ByteBuffer.allocate(26);
        pair.put(connWu.array(), 0, 13).put(connWu.array(), 0, 13);
        pair.putInt(5, 0).putInt(18, streamCtx.streamId).clear();
        if (flush) ctx.writeFlush(pair); else ctx.writeSync(pair);
        connectRecvWindow.addAndGet(windowLen);
    }

    /**
     * Send RST_STREAM frame to reset a stream.
     */
    void sendRstStreamFrame(ChannelContext ctx, int streamId, int errorCode) throws IOException {
        ByteBuffer frame = ByteBuffer.allocate(13);
        frame.put(2, (byte) 4);                                    // payload length
        frame.put(3, Http2Frame.FRAME_TYPE_RST_STREAM);             // type
        frame.putInt(5, streamId).putInt(9, errorCode).clear();
        ctx.writeFlush(frame);
    }

    /**
     * Send GOAWAY frame to gracefully close the connection.
     */
    void sendGoawayFrame(ChannelContext ctx, int lastStreamId, int errorCode) {
        ByteBuffer frame = ByteBuffer.allocate(17);
        frame.put(2, (byte) 8);                                    // payload length
        frame.put(3, Http2Frame.FRAME_TYPE_GOAWAY);                 // type
        frame.putInt(9, lastStreamId).putInt(13, errorCode).clear();
        try {
            ctx.writeFlush(frame);
        } catch (IOException ignored) {
        }
        // unblock any stream blocked waiting on send window
        wakeupSendWU();
    }

    // ==================== Stream management ====================

    /**
     * Called when the channel closes; finalizes all streams so blocked body
     * readers are released instead of leaking on abnormal disconnect.
     */
    @Override
    public void onClosed(ChannelContext ctx) {
        H2Monitor.unregister(ctx.getId());
        if (!streamMap.isEmpty()) {
            for (Http2Stream stream : streamMap.values()) {
                stream.cleanup();
            }
        }
    }

    /**
     * Remove a stream and reclaim its connection-level receive credit.
     * Idempotent: only the first successful removal reclaims (guards double reclaim).
     */
    void removeStream(int streamId) {
        Http2Stream stream = streamMap.get(streamId);
        if (stream != null) {
            stream.removed = true; // mark before removal so concurrent gets see it
            wakeupSendWU(); // unblock senders stuck in waitForSendCredit; they exit via the removed check
            if (streamMap.remove(streamId) == stream) {
                int wu = stream.pendingConnectionReclaim();
                if (wu > 0) {
                    try {
                        sendConnectionWindowUpdate(stream.ctx, wu, true);
                    } catch (IOException ignore) { // best-effort; connection may be closing
                    }
                }
            }
        }
    }

    /**
     * Get or create stream for the given stream ID.
     */
    protected abstract Http2Stream getStream(int streamId, ChannelContext ctx) throws IOException;

    // ==================== Window wakeup ====================

    void awaitSendWU() {
        try {
            synchronized (this) {
                wait(100); // wait WU
            }
        } catch (InterruptedException ignored) {
        }
    }

    void wakeupSendWU() {
        synchronized (this) {
            notifyAll();
        }
    }

    // ==================== Deferred flush ====================
    final void signalFlush(ChannelContext ctx) throws IOException {
        if (!flushPending.compareAndSet(false, true)) return;
        synchronized (ctx) {
            try {
                ctx.flush();
            } finally {
                flushPending.set(false);
            }
        }
    }

    /** Returns the {@link #INDEXED_HEADER_VALUES} index for value, or -1 if unknown
     *  or dynamic-table indexing is disabled by the peer. */
    int indexOfValue(String value) {
        if (headerIndexDisabled) return -1;
        for (int k = 0; k < INDEXED_HEADER_VALUES.length; k++) {
            if (INDEXED_HEADER_VALUES[k].equals(value)) return k;
        }
        return -1;
    }

    /**
     * Record a freshly flushed seeding entry k into the dynamic-table index model.
     * The newest entry sits at index 62; all existing entries shift up by one.
     */
    synchronized void fillHeaderIndex(int k) {
        if (k < 0 || k >= INDEXED_HEADER_VALUES.length) return;
        for (int v = 0; v < headerIndex.length(); v++) {
            int val = headerIndex.get(v);
            if (val > 0) headerIndex.set(v, val + 1);
        }
        headerIndex.set(k, 62);
    }

    // ==================== Utility ====================

    /**
     * Validate client preface magic string.
     */
    public static boolean validatePreface(byte[] buf) {
        if (buf.length != PREFACE_MAGIC_LEN) return false;
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
        return bb.getLong() == PREFACE_LONG_0
            && bb.getLong() == PREFACE_LONG_1
            && bb.getLong() == PREFACE_LONG_2;
    }

    void closeConnection(ChannelContext ctx) {
        closeConnection(ctx, 0);
    }

    /**
     * Close the connection, sending a GOAWAY frame (with the given error code) first
     * so the client receives a diagnostic reason instead of a bare FIN.
     * <p>
     * The GOAWAY is written via {@link #sendGoawayFrame} before {@code ctx.close()}.
     * Since {@code close()} performs a graceful TCP close, the GOAWAY (already copied
     * into the kernel send buffer) is delivered ahead of the FIN, so clients can read
     * the error code before the connection ends.
     *
     * @param errorCode HTTP/2 connection error code; 0 (NO_ERROR) for graceful shutdown
     */
    void closeConnection(ChannelContext ctx, int errorCode) {
        valid = false;
        lastCloseReason = "errorCode=" + errorCode;
        sendGoawayFrame(ctx, currentMaxStreamId, errorCode);
        ctx.close();
    }

    /** Fill connection-level diagnostic data for the monitor (no logic impact). */
    void fillDiagnostic(Map<String, Object> m) {
        long now = System.currentTimeMillis();
        m.put("valid", valid);
        m.put("createdAt", createdAt);
        m.put("ageMs", now - createdAt);
        m.put("lastCloseReason", lastCloseReason);
        if (ctx instanceof ChannelSSLContext) {
            ((ChannelSSLContext) ctx).fillTlsDiagnostic(m);
        }
    }
}

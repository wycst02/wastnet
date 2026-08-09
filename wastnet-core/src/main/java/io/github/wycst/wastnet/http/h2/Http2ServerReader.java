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
import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.io.IOException;

/**
 * <p> HTTP/2 server-side frame decoder. </p>
 * <p> Receives H2 frames from the client, dispatches them to stream contexts. </p>
 *
 * @author wangyc
 */
public class Http2ServerReader extends Http2MessageReader {

    /**
     * Server-side max concurrent streams limit.
     * Default 512, minimum allowed value is 100 (configured values below 100 are
     * clamped up to 100). Configure via {@code -Dwastnet.http2.max-concurrent-streams=N}.
     * <p>
     * A bounded value prevents resource exhaustion from excessive concurrent streams
     * (MadeYouReset / Rapid Reset attacks).
     */
    static final int MAX_SERVER_CONCURRENT_STREAMS = HttpConf.HTTP2_MAX_CONCURRENT_STREAMS;

    // ---- Rapid Reset (CVE-2023-44487) defense: per-connection client RST rate limit ----
    private long rstWindowStartMs;
    private int rstCountInWindow;

    // Server reply: SETTINGS frame + initial connection-level WINDOW_UPDATE (40 bytes)
    static final byte[] SERVER_REPLY_FRAMES;

    static {
        int connWu = CONNECT_RECEIVE_WINDOW_SIZE - 0xFFFF;
        int hts = Http2HpackCodec.MAX_DYNAMIC_TABLE_SIZE; // advertise the same value the decoder enforces (RFC 7541 §6.3)
        // SETTINGS frame length is 18: 3 six-byte settings (0x1/0x3/0x4) = 9*2 = 18
        SERVER_REPLY_FRAMES = new byte[]{
                0, 0, 18, Http2Frame.FRAME_TYPE_SETTINGS, 0, 0, 0, 0, 0,
                0, 1,
                (byte) (hts >> 24), (byte) (hts >> 16), (byte) (hts >> 8), (byte) hts,
                0, 3,
                (byte) (MAX_SERVER_CONCURRENT_STREAMS >> 24), (byte) (MAX_SERVER_CONCURRENT_STREAMS >> 16), (byte) (MAX_SERVER_CONCURRENT_STREAMS >> 8), (byte) MAX_SERVER_CONCURRENT_STREAMS,
                0, 4,
                (byte) (INITIAL_RECEIVE_WINDOW_SIZE >> 24), (byte) (INITIAL_RECEIVE_WINDOW_SIZE >> 16), (byte) (INITIAL_RECEIVE_WINDOW_SIZE >> 8), (byte) INITIAL_RECEIVE_WINDOW_SIZE,
                0, 0, 4, Http2Frame.FRAME_TYPE_WINDOW_UPDATE, 0, 0, 0, 0, 0,
                (byte) (connWu >> 24), (byte) (connWu >> 16), (byte) (connWu >> 8), (byte) connWu
        };
    }

    public Http2ServerReader() {
    }

    /**
     * Enforce the per-connection client RST_STREAM rate limit (Rapid Reset defense).
     * Fixed-window counting: once the inbound client RST count within
     * {@code window-seconds} exceeds {@code max-count}, the connection is closed.
     * Due to window-boundary straddling the worst-case allowed rate is ~2x max-count.
     */
    @Override
    protected void onInboundRstStream(ChannelContext ctx) {
        long now = System.currentTimeMillis();
        long windowMillis = (long) HttpConf.HTTP2_CLIENT_RST_WINDOW_SECONDS * 1000L;
        if (now - rstWindowStartMs >= windowMillis) {
            rstWindowStartMs = now;
            rstCountInWindow = 0;
        }
        if (++rstCountInWindow > HttpConf.HTTP2_CLIENT_RST_MAX_COUNT) {
            LOG.warn("HTTP/2 Rapid Reset suspected from {}: client RST_STREAM rate exceeded {} per {}s, closing connection",
                    ctx.getRemoteAddress(), HttpConf.HTTP2_CLIENT_RST_MAX_COUNT, HttpConf.HTTP2_CLIENT_RST_WINDOW_SECONDS);
            ctx.fireAbuse("RST_FLOOD");
            closeConnection(ctx);
        }
    }

    // ==================== Handshake ====================

    /**
     * <p> Handle PREFACE and Settings Frame </p>
     */
    @Override
    public void init(ChannelContext ctx) throws Exception {
        try {
            receiveClientPreface(ctx);
            replyServerSettings(ctx);
            registerGoawayOnShutdown(ctx);
        } catch (IOException e) {
            closeConnection(ctx, 1); // PROTOCOL_ERROR (handshake failure)
            throw e;
        }
        this.ctx = ctx;
        this.createdAt = System.currentTimeMillis();
        H2Monitor.register(ctx.getId(), this);
    }

    /**
     * receive client preface
     */
    private void receiveClientPreface(ChannelContext ctx) throws IOException {
        byte[] buf = new byte[PREFACE_MAGIC_LEN];
        int len = ctx.readFully(buf);
        if (len == -1) {
            valid = false;
            ctx.close();
        } else {
            valid = validatePreface(buf);
            if (!valid) {
                ctx.close();
            }
        }
    }

    public Http2ServerReader replyServerSettings(ChannelContext ctx) throws IOException {
        ctx.writeFlush(SERVER_REPLY_FRAMES);
        return this;
    }

    /**
     * Register a GOAWAY frame sender on the connection's clear listener.
     * <p>
     * Called after the HTTP/2 handshake completes ({@link #init(ChannelContext)})
     * or directly for the preface-detection path ({@code HttpChannelProtocolReader}).
     * When the server shuts down ({@code closeAllConnections()}), the listener
     * sends a GOAWAY(NO_ERROR) with the last processed stream ID, allowing the
     * client to gracefully stop using this connection.
     */
    public Http2ServerReader registerGoawayOnShutdown(final ChannelContext ctx) {
        ctx.addClearListener(() -> sendGoawayFrame(ctx, currentMaxStreamId, 0));
        return this;
    }

    // ==================== Stream context management ====================

    /**
     * Get or create stream for the given stream ID (server accepts odd IDs only).
     */
    @Override
    protected Http2Stream getStream(int streamId, ChannelContext ctx) throws IOException {
        Http2Stream stream = streamMap.get(streamId);
        if (stream == null) {
            // RFC 7540 §5.1.1: even stream IDs are server-owned; client frames on them -> PROTOCOL_ERROR.
            if ((streamId & 1) == 0) {
                closeConnection(ctx, 1);
                return null;
            }
            // unknown odd id (idle stream) -> open it
            if (streamId > currentMaxStreamId) {
                if (streamMap.size() >= MAX_SERVER_CONCURRENT_STREAMS) {
                    sendRstStreamFrame(ctx, streamId, 7); // REFUSED_STREAM
                    return null;
                }
                stream = new Http2ServerStream(this, streamId, ctx);
                streamMap.put(streamId, stream);
                currentMaxStreamId = streamId;
                H2Monitor.incrTotalOpenStreams();
            }
        }
        return stream;
    }
}

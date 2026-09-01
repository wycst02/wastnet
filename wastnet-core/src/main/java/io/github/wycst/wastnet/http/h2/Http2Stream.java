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

import io.github.wycst.wastnet.http.HttpBuf;
import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.log.Log;
import io.github.wycst.wastnet.log.LogFactory;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import io.github.wycst.wastnet.util.Utils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HTTP/2 stream context.
 * <p>
 * Represents a bidirectional flow of data between client and server within an HTTP/2 connection.
 *
 * @author wangyc
 */
public abstract class Http2Stream {

    final Log log = LogFactory.getLog(getClass());

    // Pre-encoded HPACK literal for ":status: 431" (never-indexed, ref static index 8)
    private static final byte[] H2_ERROR_431_HEADER_PAYLOAD = {
            0x18, 0x03, 0x34, 0x33, 0x31
    };

    // ==================== Abstract template methods ====================

    final Http2MessageReader reader;
    final int streamId;
    final ChannelContext ctx;
    final Map<String, Object> headers;

    // depends on the client's initial window size
    long sendWindow;
    // depends on the server's initial window size
    long receiveWindow;
    /** Stream creation timestamp for age calculation (monitoring). */
    final long createdAt = System.currentTimeMillis();
    // Atomic handoff of consumed bytes from the consumer thread to the reader thread (keeps receiveWindow reader-private).
    final AtomicInteger notifyDelta = new AtomicInteger(0);
    // Consumer-thread-only: cumulative consumed bytes since last flushed WU (reset on flush).
    private int consumedSinceWUFlush;
    boolean waitingContinuation;
    boolean endHeaders;
    boolean endStream;
    boolean requestInvoked;
    boolean handovered;
    boolean endStreamSent;
    volatile boolean removed;

    // Set when the first-window-exhaustion refill (sendWindowUpdatePair) returns
    // INIT..MAX credit to the connection window, so END_STREAM / abort only returns
    // the remaining body bytes (avoids double-counting the connection window).
    volatile int refilled;

    /**
     * Declared content-length from :content-length pseudo-header, -1 if not declared
     */
    long declaredContentLength = -1;

    /**
     * Whether body exceeds MAX_STREAM_CAPACITY_SIZE, needs streaming
     */
    boolean needStreaming;

    /**
     * Buffer to store frame payload before processing
     */
    HttpBuf frameBuf;

    /** Decoded trailer fields (RFC 7540 §8.1). Null until a trailer block is received. */
    Map<String, Object> trailers;
    /** Optional application callback fired when trailers are fully received (streaming). */
    TrailersListener trailersListener;
    /**
     * Dedicated buffer for trailer (RFC 7540 §8.1) header-block accumulation. Kept separate from
     * {@link #frameBuf} so that a streaming response (which reuses {@code frameBuf} via
     * Http2Response#buildHeaderBlock) cannot be corrupted by a concurrently arriving trailer frame
     * on the reader thread.
     */
    HttpBuf trailerBuf;

    /**
     * Total length of data frames received.
     * <p>Defined as {@code int} — the max stream flow-control threshold
     * must be capped at {@link Integer#MAX_VALUE} for consistency.
     */
    int dataFramesTotalLength;

    /**
     * Buffer to store DATA frame payloads before streaming
     */
    byte[] bodyData;

    /**
     * Streaming input stream, created when prepareSubmit is called
     */
    Http2BodyInputStream bodyStream;
    String contentType;

    // data stream
    public Http2Stream(Http2MessageReader reader, int streamId, ChannelContext ctx) {
        this.reader = reader;
        this.streamId = streamId;
        this.ctx = ctx;
        this.sendWindow = reader.streamInitSendWindowSize;
        this.receiveWindow = reader.initialReceiveWindowSize;
        this.headers = streamId == 0 ? Collections.emptyMap() : new LinkedHashMap<>();
        this.bodyData = HttpRequest.EMPTY_BODY;
    }

    /**
     * Single entry point for data stream frame dispatching.
     * <p>
     * All frames arriving on a non-zero stream are routed here and delegated
     * to the appropriate handler based on type.
     */
    void handleFrame(Http2Frame frame, ChannelContext ctx) throws IOException {
        Http2FrameType type = frame.type;
        // HEADERS: carry request headers (and optionally END_STREAM)
        if (type == Http2FrameType.HEADERS) {
            onHeadersFrame(frame, ctx);
            return;
        }
        // DATA: carry request body payload
        if (type == Http2FrameType.DATA) {
            onDataFrame(frame, ctx);
            return;
        }

        // CONTINUATION: carry CONTINUATION frames
        if (type == Http2FrameType.CONTINUATION && waitingContinuation) {
            // the scene rarely appears,  reuse header frame processing
            onHeadersFrame(frame, ctx);
            return;
        }

        // ---- control frames on a data stream ----
        switch (type) {
            // RST_STREAM: peer aborted the stream
            case RST_STREAM:
                if (frame.payloadLength != 4) {
                    reader.closeConnection(ctx, 6); // FRAME_SIZE_ERROR (RFC 7540 §6.4)
                    return;
                }
                // Rapid Reset (CVE-2023-44487) defense: count client-sent RST_STREAM.
                // Server-initiated RST is never dispatched through this path.
                reader.onInboundRstStream(ctx);
                cleanup();
                reader.removeStream(streamId);
                return;
            // SETTINGS/PING/GOAWAY are stream-0-only (RFC 7540 §6.5/§6.7/§6.8);
            // PUSH_PROMISE requires ENABLE_PUSH=1 (§8.2, this lib sets 0); all on a data stream are protocol errors.
            case SETTINGS:
            case PUSH_PROMISE:
            case PING:
            case GOAWAY:
                reader.closeConnection(ctx, 1); // PROTOCOL_ERROR
                return;
            // WINDOW_UPDATE: peer grants additional send capacity
            case WINDOW_UPDATE: {
                if (frame.payloadLength != 4) {
                    reader.closeConnection(ctx, 6); // FRAME_SIZE_ERROR (RFC 7540 §6.9)
                    return;
                }
                int increment = Http2MessageReader.readInt32(frame.frameData, frame.payloadActualOffset);
                // RFC 7540 §6.9.1: stream WU affects only this stream; connection WU only via stream 0
                synchronized (reader) {
                    if ((sendWindow += increment) > Integer.MAX_VALUE || increment <= 0) {
                        rstStream(ctx, increment <= 0 ? 1 : 3); // 0 or reserved-bit set -> PROTOCOL_ERROR; overflow -> FLOW_CONTROL_ERROR
                    }
                }
                reader.wakeupSendWU();
                return;
            }
            // CONTINUATION outside a header block sequence is a protocol error (RFC 7540 §6.10)
            case CONTINUATION:
                reader.closeConnection(ctx, 1); // PROTOCOL_ERROR
                return;
            default: // RFC 7540 §6.3: payload must be 5 octets
                if (frame.payloadLength != 5) {
                    rstStream(ctx, 6); // RFC 7540 §6.3: FRAME_SIZE_ERROR
                }
        }
    }

    /**
     * Handle an incoming HEADERS frame.
     * <p>
     * Manages HPACK header decoding, debug logging, end-of-headers parsing,
     * and early request submission (endStream or needStreaming).
     * If END_HEADERS is not set, returns early and waits for continuation.
     */
    void onHeadersFrame(Http2Frame frame, ChannelContext ctx) throws IOException {
        boolean frameEndHeaders = frame.isEndHeaders();
        boolean frameEndStream = frame.isEndStream();

        // Initial header block already complete → further header frames are trailers (RFC 7540 §8.1).
        // Low-frequency branch; hot path short-circuits on endHeaders with no extra state.
        if (endHeaders) {
            onTrailerFrame(frame, ctx, frameEndHeaders, frameEndStream);
            return;
        }
        frameBuf = appendHeaderBlock(frameBuf, frame, frameEndHeaders);

        // Guard against oversized header blocks (RFC 6585)
        if (frameBuf.size() > reader.maxHttpHeaderSize) {
            sendEarlyResponse(H2_ERROR_431_HEADER_PAYLOAD);
            return;
        }

        // END_HEADERS not set, wait for continuation HEADERS/CONTINUATION frame
        if (!frameEndHeaders) {
            waitingContinuation = true;
            return;
        }

        if (!decodeHeaderBlock(frameBuf, ctx, headers, false)) return;

        // Parse and validate the initial request headers. endHeaders() sets the endHeaders flag.
        try {
            endHeaders();
        } catch (Exception e) { // malformed/incomplete request headers
            // Stream error, not a connection close: HPACK decoded fine, so a malformed request is
            // rejected per-stream. RFC 7540 §8.1.3.1 requires a connection error, but is relaxed here.
            rstStream(ctx, 1); // PROTOCOL_ERROR
            return;
        }
        // The block is complete: clear the continuation flag so any stray frame afterwards is
        // treated as a (protocol-error) trailer, never as an orphaned continuation.
        waitingContinuation = false;

        if (frameEndStream) { // END_STREAM set in HEADERS frame, no body
            this.endStream = true;
            this.bodyStream = Http2BodyInputStream.EMPTY;
            submit();
        }
    }

    /**
     * Handle a header block that arrives after the initial request headers are complete — i.e. an
     * HTTP/2 trailer block (RFC 7540 §8.1). Low-frequency path, only reached from
     * {@link #onHeadersFrame(Http2Frame, ChannelContext)} when {@code endHeaders} is already true.
     */
    void onTrailerFrame(Http2Frame frame, ChannelContext ctx, boolean frameEndHeaders, boolean frameEndStream) throws IOException {
        // A fresh HEADERS frame after the initial block must carry END_STREAM (trailers end the
        // stream). CONTINUATION frames (waitingContinuation == true) merely continue the block and
        // carry no END_STREAM of their own, so they are allowed through.
        if (!waitingContinuation && (!frameEndStream || endStream)) {
            rstStream(ctx, 1); // PROTOCOL_ERROR
            return;
        }

        trailerBuf = appendHeaderBlock(trailerBuf, frame, frameEndHeaders);

        // Oversized trailer: never send 431 here — the response may already be streaming (DATA sent),
        // an extra HEADERS would corrupt the stream. Reset the stream instead.
        if (trailerBuf.size() > reader.maxHttpHeaderSize) {
            rstStream(ctx, 1); // PROTOCOL_ERROR
            return;
        }

        // END_HEADERS not set, wait for continuation CONTINUATION frame
        if (!frameEndHeaders) {
            waitingContinuation = true;
            return;
        }

        // Trailer block: pseudo-header fields are forbidden (RFC 7540 §8.1.2.1).
        if (!decodeHeaderBlock(trailerBuf, ctx, trailers = new LinkedHashMap<>(), true)) return;

        // Trailer block fully received. Never call endHeaders() — it expects request pseudo-headers
        // and would NPE / mis-validate. Finalize the stream instead.
        waitingContinuation = false;
        this.endStream = true;
        // Close the body input stream (streaming) so the application's read loop terminates.
        if (bodyStream != null && bodyStream != Http2BodyInputStream.EMPTY) {
            bodyStream.endStream();
        }
        fireTrailers();
        // Non-streaming: this trailer frame is the submit trigger (body already buffered).
        if (!requestInvoked) {
            submit();
        }
    }

    /**
     * Short-circuits the stream by sending an immediate HEADERS-frame reply (END_HEADERS | END_STREAM)
     * carrying the given pre-encoded {@code :status} payload, then removes the stream so the request
     * never reaches the business layer.
     */
    private void sendEarlyResponse(byte[] headerPayload) throws IOException {
        ByteBuffer frame = createFrameBuffer(9 + headerPayload.length, headerPayload.length,
                Http2Frame.FRAME_TYPE_HEADERS, Http2Frame.END_HEADERS | Http2Frame.END_STREAM);
        frame.put(headerPayload).flip();
        writeFrame(frame);
        reader.removeStream(streamId);
    }

    /**
     * Send an RST_STREAM with the given error code and remove this stream from the connection.
     */
    private void rstStream(ChannelContext ctx, int errorCode) throws IOException {
        cleanup();
        reader.sendRstStreamFrame(ctx, streamId, errorCode);
        reader.removeStream(streamId);
    }

    /**
     * Append a HEADERS/CONTINUATION payload to {@code buf}, allocating a private buffer on first use.
     */
    private HttpBuf appendHeaderBlock(HttpBuf buf, Http2Frame frame, boolean isEndHeaders) {
        byte[] frameData = frame.frameData;
        int offset = frame.payloadActualOffset;
        int len = frame.payloadActualLength;
        if (buf == null) {
            buf = HttpBuf.of(64);
            if (isEndHeaders) {
                return buf.replace(frameData, offset, len);
            }
        }
        buf.append(frameData, offset, len);
        return buf;
    }

    /**
     * HPACK-decode {@code buf} into {@code target}; returns false (closing the connection on
     * COMPRESSION_ERROR) if decoding fails.
     */
    private boolean decodeHeaderBlock(HttpBuf buf, ChannelContext ctx, Map<String, Object> target, boolean forbidPseudo) {
        try {
            reader.http2HpackCodec.decodeTo(buf.getBuf(), buf.getBegin(), buf.size(), target, forbidPseudo);
            buf.reset(); // reset swaps buf back to its own private array, dropping any reference to the shared connection read buffer
            return true;
        } catch (Throwable throwable) {
            log.error("Hpack Error, Stream Id: " + streamId + "-> Hpack Header Bytes: " + Utils.printHexString(buf.toBytes(), ' '));
            reader.closeConnection(ctx, 9); // COMPRESSION_ERROR
            return false;
        }
    }

    /**
     * Handle an incoming DATA frame.
     * <p>
     * Manages all DATA-related logic including protocol checks, window updates
     * (connection-level and stream-level, both consumption-driven after invocation,
     * receive-driven before invocation),
     * body accumulation / streaming, and discard mode for early-response cases.
     */
    void onDataFrame(Http2Frame frame, ChannelContext ctx) throws IOException {
        int len = frame.payloadActualLength;

        // DATA before END_HEADERS (idle stream) -> connection-level PROTOCOL_ERROR (RFC 7540 §5.1).
        if (!endHeaders) {
            reader.closeConnection(ctx, 1); // PROTOCOL_ERROR
            return;
        }

        // Drain consumed bytes handed off via notifyDelta before the window check.
        receiveWindow += notifyDelta.getAndSet(0);

        // Frame has been received; decrement windows accordingly
        if (reader.connectRecvWindow.addAndGet(-len) < 0) { // RFC 7540 §6.9.1: conn-level window < 0 -> FLOW_CONTROL_ERROR: close connection, drop frame
            reader.closeConnection(ctx, 3);
            return;
        }

        if ((receiveWindow -= len) < 0) { // RFC 7540 §6.9.1: stream-level window < 0 -> FLOW_CONTROL_ERROR: RST this stream only (covers streaming & non-streaming)
            rstStream(ctx, 3);
            return;
        }

        // Post-invocation: consumption-driven stream + connection WU
        if (requestInvoked) {
            feedDataFrame(frame);
            return;
        }

        // a. End stream: submit request (non-streaming) + restore connection-level WU
        if (frame.isEndStream()) {
            if (frameBuf.isEmpty()) {
                // First (and only) DATA frame is also end-stream: directly copy payload,
                // avoids appendBody's empty-array merge for the common small-request case.
                bodyData = frame.actualPayload();
            } else {
                // Already accumulated with previous frames: add last and merge
                frameBuf.append(frame.frameData, frame.payloadActualOffset, frame.payloadActualLength);
                bodyData = frameBuf.toBytesAndClear();
            }
            prepareSubmit(true);
            // Return consumed body bytes to the connection window, subtracting the first-exhaustion refill to avoid double-counting.
            int wu;
            if (bodyData.length > 0 && (wu = refilled > 0 ? bodyData.length - refilled : bodyData.length) > 0) {
                reader.sendConnectionWindowUpdate(ctx, wu);
            }
            return;
        }

        // Non-end-stream: accumulate frame into frameBuf for later merging.
        // Empty DATA frames bypass all release conditions; drop them to avoid OOM.
        if (len == 0) return;
        frameBuf.append(frame.frameData, frame.payloadActualOffset, frame.payloadActualLength);
        dataFramesTotalLength += len;
        // b. recvWindow > 0: only check connection-level WU
        if (receiveWindow > 0) {
            restoreConnectionWindow();
            return;
        }

        // c. recvWindow == 0
        if (reader.streamEarly) {
            // First window exhaustion => stream now (ring buffer = INITIAL_RECEIVE_WINDOW_SIZE)
            startStreaming();
            return;
        }
        if (dataFramesTotalLength < reader.maxStreamCapacitySize) {
            // First window exhaustion: send WU to refill
            // newWindowSize = MAX_STREAM_CAPACITY_SIZE - dataFramesTotalLength (remaining buffer space)
            int newWindowSize = reader.maxStreamCapacitySize - dataFramesTotalLength;
            reader.sendWindowUpdatePair(ctx, this, newWindowSize, true);
            receiveWindow = refilled = newWindowSize;
        } else if (dataFramesTotalLength == reader.maxStreamCapacitySize) {
            startStreaming();
        }
    }

    /**
     * Merge accumulated DATA frames and switch this stream to streaming mode.
     */
    private void startStreaming() {
        bodyData = frameBuf.toBytesAndClear();
        needStreaming = true;
        prepareSubmit(false);
    }

    /**
     * Get headers map.
     */
    public Map<String, Object> headers() {
        return headers;
    }

    /**
     * Get the trailer fields (RFC 7540 §8.1) received after the request body.
     * Returns an empty map if no trailers were sent.
     */
    public Map<String, Object> getTrailers() {
        return trailers == null ? Collections.emptyMap() : trailers;
    }

    /**
     * Register a callback invoked when the trailer block is fully received.
     * <p>
     * Effective only in <b>streaming</b> mode: the request is dispatched before the trailer
     * arrives, so the listener can be registered in time. The callback fires on the reader thread
     * at the moment the trailer block is decoded — concurrently with the application reading the
     * body (its {@code bodyStream.read()} returns {@code -1} at the same time). For non-streaming
     * requests, prefer {@link #getTrailers()} after the request is handed over.
     */
    public void setTrailersListener(TrailersListener listener) {
        this.trailersListener = listener;
    }

    /**
     * Fire the registered {@link TrailersListener}. Called on the reader thread right after the
     * trailer block is decoded and the body stream is marked end-of-stream (read() returns -1).
     * No-op if no listener is registered.
     */
    void fireTrailers() {
        if (trailersListener != null) {
            trailersListener.onTrailers(getTrailers());
        }
    }

    /**
     * Get content length.
     */
    public long getContentLength() {
        if (needStreaming) {
            if (declaredContentLength != -1) {
                return declaredContentLength;
            }
            if (bodyStream.ended) {
                return bodyStream.totalLength;
            }
            throw new IllegalStateException("content-length not declared and bodyStream not ended");
        } else {
            return bodyData.length;
        }
    }

    /**
     * Get accumulated body data. Note: in streaming mode, this only returns
     * initial data before streaming started, not the complete body.
     * Use bodyStream to read complete data in streaming mode.
     */
    public byte[] getBodyData() {
        if (needStreaming) {
            throw new IllegalStateException("bodyData not available in streaming mode; use bodyStream() to read the complete body");
        }
        return bodyData;
    }

    void prepareSubmit(boolean endStream) {
        // Only create bodyStream for streaming (endStream=false); endStream=true handled in onHeadersFrame
        if (!(this.endStream = endStream)) {
            this.bodyStream = new Http2BodyInputStream(bodyData, this);
        }
        submit();
    }

    /**
     * Mark this stream as handed over to internal logic (e.g. proxy forwarding).
     * The framework will skip normal stream cleanup after handler returns.
     */
    void handover() {
        this.handovered = true;
    }

    /**
     * Restore connection-level window if below stream-level window.
     */
    void restoreConnectionWindow() throws IOException {
        long curRecvWindow = reader.connectRecvWindow.get();
        if (curRecvWindow < receiveWindow) { // ensure connection-level window is not below the stream-level window; top up to match if so
            int increment = (int) (receiveWindow - curRecvWindow);
            reader.sendConnectionWindowUpdate(ctx, increment, true);
        }
    }

    /**
     * Streaming mode: feed the arrived DATA payload directly into the app body stream (consume-as-arrive);
     * a rejected feed (consumer backpressure) is treated as FLOW_CONTROL_ERROR -> RST this stream.
     */
    void feedDataFrame(Http2Frame frame) throws IOException {
        boolean frameEnd = frame.isEndStream();
        this.endStream = frameEnd;
        if (bodyStream != null) {
            // feed() copies bytes into its own buffer and does not retain the reference.
            if (!bodyStream.feed(frame.frameData, frame.payloadActualOffset, frame.payloadActualLength)) {
                log.warn("feed rejected, send RST_STREAM(FLOW_CONTROL_ERROR) streamId={} recvWindow={}", streamId, receiveWindow);
                reader.sendRstStreamFrame(ctx, streamId, 3);
                return;
            }
            if (frameEnd) bodyStream.endStream();
        }
    }

    void completeRequest() throws IOException {
        if (!endStream) {
            // Abort the client's remaining body upload; NO_ERROR keeps the early response
            // valid (RFC 7540 §8.1). In-flight DATA is tolerated by the reader (§5.1).
            reader.sendRstStreamFrame(ctx, streamId, 0);
        }
    }

    /**
     * Finalize the stream lifecycle: unblock any thread blocked reading the
     * request body and release the body stream. Reused on RST and abnormal close.
     */
    void cleanup() {
        if (needStreaming && !bodyStream.ended) {
            bodyStream.endStream();
        }
    }

    /**
     * Clean up stream resources: remove from reader to stop new DATA frames.
     * Connection-level window reclaim (incl. unconsumed streaming bytes) is handled by
     * {@link Http2MessageReader#removeStream(int)}. Fallback: if END_STREAM was never sent (e.g. commit
     * omitted), send a terminal frame.
     */
    void completeStream() {
        if (!endStreamSent) {
            try {
                ByteBuffer endFrame = createFrameBuffer(9, 0, Http2Frame.FRAME_TYPE_DATA, Http2Frame.END_STREAM);
                endFrame.flip();
                writeFrame(endFrame);
            } catch (IOException ignore) {
            }
        }
        reader.removeStream(streamId);
    }

    /**
     * Remaining connection-level credit owed at stream end (streaming=unconsumed,
     * non-streaming=received but END_STREAM missed). Solely called from removeStream, caller guards wu &gt; 0.
     */
    int pendingConnectionReclaim() {
        if (needStreaming) {
            return (int) (bodyStream.totalLength - bodyStream.getConsumed());
        }
        return !endStream ? dataFramesTotalLength - refilled : 0;
    }

    /** Fill per-stream diagnostic data for the monitor (no logic impact). */
    void fillDiagnostic(Map<String, Object> m) {
        m.put("streamId", streamId);
        m.put("createdAt", createdAt);
        m.put("ageMs", System.currentTimeMillis() - createdAt);
        m.put("endStream", endStream);
        m.put("removed", removed);
        m.put("requestInvoked", requestInvoked);
        m.put("handovered", handovered);
        m.put("waitingContinuation", waitingContinuation);
        m.put("sendWindow", sendWindow);
        m.put("recvWindow", receiveWindow);
        if (bodyStream != null) {
            long unconsumed = bodyStream.totalLength - bodyStream.getConsumed();
            m.put("bodyUnconsumed", unconsumed);
        } else {
            m.put("bodyUnconsumed", 0L);
        }
    }

    /** Flush buffered bytes under the channel lock, safe against concurrent writes. */
    void flushCtx() throws IOException {
        synchronized (ctx) {
            ctx.flush();
        }
    }

    /**
     * Get the body input stream. Creates a stream wrapper on demand
     * when the app reads via InputStream instead of getBodyData().
     */
    public InputStream getInputStream() {
        if (bodyStream == null) {
            bodyStream = new Http2BodyInputStream(bodyData, this);
            // Non-streaming request: body fully buffered in bodyData, mark ended so read() returns -1 after draining.
            if (endStream) {
                bodyStream.endStream();
            }
        }
        return bodyStream;
    }

    /**
     * Called by Http2BodyInputStream after read() to update WU.
     * Sends WU for consumed bytes and updates both stream and connection level windows.
     */
    void notifyConsumed(int consumed) {
        if (consumed <= 0) return;
        try {
            consumedSinceWUFlush += consumed;
            notifyDelta.addAndGet(consumed);
            boolean flush = consumedSinceWUFlush >= 4096 || endStream;
            reader.sendWindowUpdatePair(ctx, this, consumed, flush);
            if (flush) consumedSinceWUFlush = 0;
        } catch (IOException ignore) { // Best-effort; connection may be closing
        }
    }

    // ==================== Outgoing frame helpers ====================

    /**
     * Unified entry point for all outgoing (response) frames.
     * <p>
     * Writes a pre-built frame to the channel. When {@link Http2MessageReader#DEBUG}
     * is enabled, prints frame details to stdout with "[Server]" prefix
     * (vs "[Client]" for incoming frames).
     */
    void writeFrame(ByteBuffer frame) throws IOException {
        writeFrame(frame, true);
    }

    // Write a single frame; flush only when requested so callers can batch frames and
    // flush once (e.g. HEADERS + DATA in a single commit).
    void writeFrame(ByteBuffer frame, boolean flush) throws IOException {
        if (prepareFrame(frame)) {
            ctx.writeSync(frame);
            if (flush) reader.signalFlush(ctx);
        }
    }

    /**
     * Batch entry point for outgoing (response) frames.
     * <p>
     * Applies the same per-frame preconditions as {@link #writeFrame(ByteBuffer)}
     * (endStream guard + DEBUG logging), then writes all frames contiguously in a
     * single synchronized(ctx) critical section via {@link ChannelContext#writeAllFlush}.
     * This guarantees an atomic frame sequence (e.g. a HEADERS block plus its
     * CONTINUATION frames) is not interleaved by other streams, per RFC 7540 §6.2/§6.10.
     *
     * @param frames the frames to write as one atomic, flushed unit
     * @throws IOException if the channel is closed
     */
    void writeFrames(List<ByteBuffer> frames) throws IOException {
        // Frames are produced only by framework-internal code (h2 package), so we trust them
        // not to write after END_STREAM. Run per-frame preconditions for endStreamSent bookkeeping
        // and DEBUG logging, then flush the caller-owned list once with no extra allocation.
        for (ByteBuffer frame : frames) {
            prepareFrame(frame);
        }
        ctx.writeAllFlush(frames);
    }

    /**
     * Per-frame precondition for outgoing (response) frames:
     * rejects writes after END_STREAM, marks END_STREAM state, and emits DEBUG logging.
     *
     * @return {@code true} if the frame should be sent, {@code false} if rejected
     */
    boolean prepareFrame(ByteBuffer frame) {
        // Reject any frame write after END_STREAM has been sent
        if (endStreamSent) {
            byte[] headerBytes = new byte[9];
            for (int i = 0; i < 9; ++i) headerBytes[i] = frame.get(i);
            log.error("[H2] Warning: writeFrame after END_STREAM on stream " + streamId
                    + ", header: " + Utils.printHexString(headerBytes, ' '));
            return false;
        }
        int frameFlags = frame.get(4);
        if ((frameFlags & Http2Frame.END_STREAM) != 0) { // Check frame flags (offset 4) for END_STREAM
            endStreamSent = true;
        }

        if (Http2MessageReader.DEBUG) {
            synchronized (reader) {
                for (Http2Frame h2Frame : Http2Frame.fromByteBuffer(frame)) {
                    if (h2Frame.type != Http2FrameType.DATA) {
                        log.debug("[{} {}] streamId={} length={}\n{}",
                                debugPrefix(), h2Frame.type, h2Frame.streamId, h2Frame.payloadLength, h2Frame.toHexDump());
                    } else {
                        log.debug("[{} {}] streamId={} length={}",
                                debugPrefix(), h2Frame.type, h2Frame.streamId, h2Frame.payloadLength);
                    }
                }
            }
        }
        return true;
    }

    void writeDataFrame(ByteBuffer frame, int payloadLength) throws IOException {
        if (acquireSendWindow(payloadLength)) {
            writeFrame(frame);
        }
    }

    /**
     * Core send-window credit acquisition. Waits inside the reader lock until the
     * required credit is available, then debits both stream and connection windows.
     * <p>
     * When {@code requireAll} is {@code true}, the method waits until both windows
     * are at least {@code maxPayload} before returning — the result is always
     * exactly {@code maxPayload} or {@code 0} (timeout / GOAWAY). Callers that
     * build fixed-size frames (e.g. {@link #writeDataFrame})
     * use this through {@link #acquireSendWindow}.
     * <p>
     * When {@code requireAll} is {@code false}, the method waits only until credit
     * is non-zero, then returns the smaller of the two windows (capped at
     * {@code maxPayload}). Callers that build variable-size DATA frames (e.g.
     * {@code sendChunkedData}) use this through {@link #acquirePartialSendWindow}.
     */
    private int waitForSendCredit(int maxPayload, boolean requireAll) {
        long deadline = System.currentTimeMillis() + reader.flowControlWaitTimeoutMs;
        synchronized (reader) {
            int available;
            while (true) {
                available = (int) Math.min(Math.min(sendWindow, reader.connectSendWindow), maxPayload);
                if (requireAll ? (available == maxPayload) : (available > 0)) break;
                if (!reader.valid || removed) return 0; // stop sending once stream is reset/removed
                if (System.currentTimeMillis() >= deadline) {
                    reader.closeConnection(ctx, 3);
                    return 0;
                }
                reader.awaitSendWU();
            }
            sendWindow -= available;
            reader.connectSendWindow -= available;
            return available;
        }
    }

    /**
     * Acquire send-window credit for exactly {@code payloadLength} bytes (all-or-nothing).
     * See {@link #waitForSendCredit} for details.
     * <p>
     * Returns {@code false} if the peer sent GOAWAY, or flow control timed out.
     */
    boolean acquireSendWindow(int payloadLength) {
        return waitForSendCredit(payloadLength, true) == payloadLength;
    }

    /**
     * Acquire send-window credit for as many bytes as currently available (up to
     * {@code maxPayload}), returning the actual count.
     * See {@link #waitForSendCredit} for details.
     * <p>
     * Returns 0 on GOAWAY or timeout.
     */
    int acquirePartialSendWindow(int maxPayload) throws IOException {
        return waitForSendCredit(maxPayload, false);
    }

    /**
     * Pre-fill the 9-byte frame header and return a ByteBuffer positioned at 9.
     *
     * @param capacity total buffer capacity (must be 9 + payload size)
     * @param length   payload length for the 24-bit length field
     * @param type     frame type (e.g. 0x00 for DATA, 0x01 for HEADERS)
     * @param flags    flags byte
     */
    public ByteBuffer createFrameBuffer(int capacity, int length, int type, int flags) {
        ByteBuffer buf = ByteBuffer.allocate(capacity);
        buf.put((byte) (length >> 16))
                .put((byte) (length >> 8))
                .put((byte) length)
                .put((byte) type)
                .put((byte) flags)
                .putInt(streamId);
        return buf;
    }

    // ==================== H2 response encoding (proxy) ====================
    /**
     * Max payload per DATA frame for sending, limited by both the client's
     * SETTINGS_MAX_FRAME_SIZE and the local channel write buffer capacity.
     */
    public int sendChunkSize() {
        return Math.min(reader.maxSendPayloadSize, ctx.getWriteBufferSize() - 9);
    }

    /**
     * Write a complete HPACK header block as a HEADERS frame. When the block
     * exceeds the single-frame limit it is split into HEADERS + CONTINUATION
     * frames; END_HEADERS (and END_STREAM if {@code endStream}) is set only
     * on the final frame.
     *
     * @param payload   full HPACK-encoded header block
     * @param endStream true to set END_STREAM on the final header frame (no body follows)
     */
    void writeHeadersFrame(byte[] payload, boolean endStream) throws IOException {
        writeHeadersFrame(payload, endStream, true);
    }

    void writeHeadersFrame(byte[] payload, boolean endStream, boolean flush) throws IOException {
        int max = sendChunkSize();
        int endFlags = (endStream ? Http2Frame.END_STREAM : 0) | Http2Frame.END_HEADERS;
        if (payload.length <= max) {
            ByteBuffer f = createFrameBuffer(9 + payload.length, payload.length,
                    Http2Frame.FRAME_TYPE_HEADERS, endFlags);
            f.put(payload).flip();
            writeFrame(f, flush);
            return;
        }
        // Collect HEADERS + all CONTINUATION frames, then write atomically as one unit.
        // This prevents other streams from interleaving between HEADERS and CONTINUATION
        // (RFC 7540 §6.2/§6.10) and flushes only once.
        List<ByteBuffer> frames = new ArrayList<>();
        ByteBuffer head = createFrameBuffer(9 + max, max, Http2Frame.FRAME_TYPE_HEADERS, 0); // HEADERS carries no END_HEADERS/END_STREAM
        head.put(payload, 0, max).flip();
        frames.add(head);
        int off = max;
        while (off < payload.length) {
            int len = Math.min(payload.length - off, max);
            boolean last = off + len == payload.length;
            ByteBuffer cont = createFrameBuffer(9 + len, len, Http2Frame.FRAME_TYPE_CONTINUATION,
                    last ? endFlags : 0); // flags (END_HEADERS [+END_STREAM]) only on the final frame
            cont.put(payload, off, len).flip();
            frames.add(cont);
            off += len;
        }
        writeFrames(frames);
    }

    /**
     * Template method: parse and validate headers after HPACK decode.
     * <p>
     * Server: parses request pseudo-headers ({@code :method}, {@code :scheme}, {@code :path}, {@code :authority})
     * Client: parses response pseudo-headers ({@code :status})
     */
    protected final void endHeaders() {
        endHeaders = true;
        onEndHeaders();
    }

    /**
     * Hook invoked by {@link #endHeaders()} after setting the flag.
     * Subclasses implement their own header parsing logic.
     */
    protected abstract void onEndHeaders();

    /**
     * Called when headers are fully received and body is ready (or stream ended).
     * <p>
     * Server: creates {@link Http2Request} and dispatches to handler.
     * Client: notifies response callback.
     */
    protected abstract void submit();

    /**
     * Get the HTTP/2 stream identifier.
     *
     * @return stream ID (odd for client-initiated, even for server-initiated)
     */
    public int getStreamId() {
        return streamId;
    }

    /**
     * Whether this stream has been removed from the reader (e.g. via RST_STREAM).
     */
    public boolean isRemoved() {
        return removed;
    }

    /**
     * Returns log prefix for DEBUG output, e.g. {@code "Server "} or {@code "Client "}.
     */
    public abstract String debugPrefix();
}

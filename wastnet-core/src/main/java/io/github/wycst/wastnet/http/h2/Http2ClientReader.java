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

import io.github.wycst.wastnet.http.HttpDecodedResponse;
import io.github.wycst.wastnet.http.HttpVersion;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <p> HTTP/2 client-side frame decoder. </p>
 * <p> Sends client preface, receives H2 frames from the server, dispatches to stream contexts. </p>
 *
 * @author wangyc
 */
public class Http2ClientReader extends Http2MessageReader {

    /**
     * Client preface settings: no push, default initial window, large header table.
     */
    static final byte[] INIT_CLIENT_SETTINGS;

    static {
        int initialRecvWindow = CONNECT_RECEIVE_WINDOW_SIZE;
        // Build SETTINGS frame: SETTINGS_ENABLE_PUSH(0) + SETTINGS_INITIAL_WINDOW_SIZE = 21 bytes
        // id=2 (ENABLE_PUSH), value=0 (disabled)
        // id=4 (INITIAL_WINDOW_SIZE), value=initialRecvWindow
        INIT_CLIENT_SETTINGS = new byte[]{
                0, 0, 12, 4, 0, 0, 0, 0, 0,
                0, 2,
                0, 0, 0, 0,   // ENABLE_PUSH = 0
                0, 4,
                (byte) (initialRecvWindow >> 24), (byte) (initialRecvWindow >> 16),
                (byte) (initialRecvWindow >> 8), (byte) initialRecvWindow
        };
    }

    /**
     * Next client-initiated stream ID (odd, starts at 1, increments by 2).
     * Atomic for concurrent access (H2 multiplexing in proxy mode).
     */
    private final AtomicInteger nextStreamId = new AtomicInteger(1);

    public Http2ClientReader() {
        // Switch the codec to client (response) decoding mode so that the
        // :status pseudo-header from upstream servers is accepted (RFC 7541 §8.1.2.1).
        http2HpackCodec.client();
    }

    @Override
    protected String debugPeerPrefix() {
        return "Server ";
    }

    /**
     * Allocate the next stream ID for a client-initiated request. Thread-safe.
     *
     * @return odd stream ID
     */
    public int nextStreamId() {
        return nextStreamId.getAndAdd(2);
    }

    // ==================== Handshake ====================

    /**
     * Client H2 handshake: send preface + SETTINGS, wait for server SETTINGS + ACK.
     */
    @Override
    public void init(ChannelContext ctx) throws Exception {
        try {
            // 1. Send client preface magic
            ctx.writeFlush(CLIENT_CONNECTION_PREFACE);
            // 2. Send client SETTINGS
            ctx.writeFlush(INIT_CLIENT_SETTINGS);
        } catch (IOException e) {
            ctx.close();
            throw e;
        }
    }

    // ==================== Stream context management ====================

    @Override
    protected Http2Stream getStream(int streamId, ChannelContext ctx) throws IOException {
        return getOrCreateStream(streamId, ctx);
    }

    /**
     * Get or create stream for the given stream ID.
     */
    public Http2ClientStream getOrCreateStream(int streamId, ChannelContext ctx) throws IOException {
        Http2Stream stream = streamMap.get(streamId);
        if (stream == null && streamId > currentMaxStreamId) {
            stream = new Http2ClientStream(this, streamId, ctx);
            streamMap.put(streamId, stream);
            currentMaxStreamId = streamId;
        }
        return (Http2ClientStream) stream;
    }

    // ── Build decoded response from client stream (for proxy adapter) ──

    public HttpDecodedResponse buildResponse(Http2ClientStream clientStream) {
        long contentLength = clientStream.declaredContentLength;
        if (contentLength <= 0) {
            contentLength = clientStream.bodyData.length;
        }
        if (clientStream.needStreaming) {
            return new HttpDecodedResponse(HttpVersion.HTTP_2,
                    clientStream.statusCode, "", clientStream.headers, clientStream.bodyData, contentLength, clientStream.contentType,
                    true, clientStream.bodyStream);
        }
        return new HttpDecodedResponse(HttpVersion.HTTP_2,
                clientStream.statusCode, "", clientStream.headers, clientStream.bodyData, contentLength, clientStream.contentType);
    }
}

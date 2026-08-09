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
package io.github.wycst.wastnet.http.proxy;

import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Adapter for bidirectional HTTP proxy data relay.
 * <p>
 * Different protocol scenarios (H1↔H1, H2↔H2, H2→H1, H1→H2) can be handled
 * by different adapter implementations, each transforming the byte stream
 * as needed before forwarding.
 * </p>
 *
 * @author wangyc
 */
public interface HttpProxyAdapter {

    /**
     * Called when data is received from one side of the proxy.
     * <p>
     * The implementation should transform (if needed) and forward the data
     * to the opposite side via {@link HttpProxyConnection#clientCtx} or
     * {@link HttpProxyConnection#targetCtx}.
     *
     * @param buffer   received data (in flipped state, ready to read)
     * @param writeCtx the target channel context to write to
     * @param isTarget whether the data originated from the target server
     * @param conn     the proxy connection context
     * @throws IOException if an I/O error occurs during processing
     */
    void onData(ByteBuffer buffer, ChannelContext writeCtx, boolean isTarget, HttpProxyConnection conn) throws IOException;

    /**
     * Forward the initial request to the target server.
     * <p>
     * For same-protocol adapters (PASSTHROUGH), delegates the request bytes directly.
     * For cross-protocol adapters (H2_H1), converts the request to the target protocol first.
     *
     * @param request   the incoming request
     * @param targetCtx the target channel context
     * @throws Throwable if an error occurs during forwarding
     */
    void sendRequest(HttpRequest request, ChannelContext targetCtx) throws Throwable;

    /**
     * Wait for the response from the target server and process it.
     * <p>
     * For same-protocol adapters (PASSTHROUGH), this is a no-op.
     * For cross-protocol adapters (H2_H1), waits for response decoding and writes H2 response.
     *
     * @param targetCtx the target channel context
     */
    void receiveResponse(ChannelContext targetCtx) throws InterruptedException;

    /**
     * Atomically try to acquire this adapter for a new request.
     * <p>
     * PASSTHROUGH (H1↔H1) always succeeds (true).
     * H2→H1 uses CAS to prevent concurrent streams sharing the same H1 connection.
     *
     * @return true if acquired successfully (caller may use this adapter), false if busy
     */
    boolean tryAcquire();

    // ==================== Built-in adapter instances ====================

    /**
     * Pass-through adapter: raw byte relay without transformation.
     * <p>
     * Handles the first-response upgrade detection (WebSocket / h2c) before forwarding.
     * Only suitable for H1↔H1. HTTP/2 requires frame-level decoding even for same-protocol proxy.
     */
    HttpProxyAdapter PASSTHROUGH = new HttpProxyAdapter() {
        @Override
        public void onData(ByteBuffer buffer, ChannelContext writeCtx, boolean isTarget, HttpProxyConnection conn) throws IOException {
            writeCtx.write(buffer);
            writeCtx.flush();
        }

        @Override
        public void sendRequest(HttpRequest request, ChannelContext targetCtx) throws Throwable {
            request.delegate(targetCtx);
        }

        @Override
        public void receiveResponse(ChannelContext targetCtx) {
        }

        @Override
        public boolean tryAcquire() {
            return true;
        }
    };
}

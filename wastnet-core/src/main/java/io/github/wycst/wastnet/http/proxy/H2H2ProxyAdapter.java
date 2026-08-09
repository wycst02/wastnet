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

import io.github.wycst.wastnet.http.HttpDecodedResponse;
import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.h2.*;
import io.github.wycst.wastnet.log.Log;
import io.github.wycst.wastnet.log.LogFactory;
import io.github.wycst.wastnet.socket.handler.ChannelHandler;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTTP/2 to HTTP/2 proxy adapter.
 * <p>
 * Forwards H2 requests to an H2 target server by re-encoding the request
 * headers via HPACK and sending H2 frames (HEADERS + DATA) on a client-initiated
 * stream. On the target response, raw H2 frames from the target connection are
 * decoded by {@link Http2ClientReader}, and the resulting
 * {@link Http2ClientStream} is converted back to H2 frames for the original
 * client stream.
 * <p>
 * Supports H2 multiplexing — multiple concurrent streams share the same target
 * connection. Stream ID mapping ({@code targetStreamId &rarr; clientServerStream})
 * is maintained for response routing.
 * <p>
 * Implements {@link ChannelHandler} to receive decoded H2 responses
 * from {@link Http2ClientReader} via {@code targetCtx.invokeHandle()}.
 * <p>
 * Singleton per proxy connection.
 *
 * @author wangyc
 */
public class H2H2ProxyAdapter extends ChannelHandler<Object> implements HttpProxyAdapter {

    static final Log log = LogFactory.getLog(H2H2ProxyAdapter.class);

    final HttpProxyConnection connection;
    final Http2ClientReader clientReader;
    final ConcurrentHashMap<Integer, Http2ServerStream> serverStreamMap =
            new ConcurrentHashMap<Integer, Http2ServerStream>();

    public H2H2ProxyAdapter(HttpProxyConnection connection) {
        this.connection = connection;
        this.clientReader = new Http2ClientReader();
        connection.targetCtx.setChannelHandler(this);
        try {
            clientReader.init(connection.targetCtx);
        } catch (Exception e) {
            throw new RuntimeException("H2 handshake with target failed", e);
        }
    }

    // ==================== Request: H2 -> H2 ====================

    @Override
    public void sendRequest(HttpRequest request, ChannelContext targetCtx) throws Throwable {
        Http2Request h2Request = (Http2Request) request;
        Http2ServerStream serverStream = h2Request.stream();

        // Lock only for stream ID allocation + HEADERS frame (lightweight)
        boolean hasBody;
        Http2ClientStream targetStream;
        synchronized (clientReader) {
            int targetStreamId = clientReader.nextStreamId();
            targetStream = clientReader.getOrCreateStream(targetStreamId, targetCtx);
            serverStreamMap.put(targetStreamId, serverStream);
            try {
                hasBody = Http2Helper.sendH2RequestHeaders(serverStream, targetStream);
            } catch (Throwable t) {
                serverStreamMap.remove(targetStreamId);
                log.error("[H2H2Proxy] sendRequest error: {}", t.getMessage());
                if (connection.isTargetClosed()) {
                    connection.close();
                }
                Http2Helper.sendGatewayError(serverStream);
                return;
            }
        }

        // Body DATA frames outside lock (may be large, avoid HOL blocking)
        if (hasBody) {
            try {
                Http2Helper.sendH2RequestBody(serverStream, targetStream);
            } catch (Throwable t) {
                log.error("[H2H2Proxy] sendRequestBody error: {}", t.getMessage());
            }
        }
    }

    @Override
    public void receiveResponse(ChannelContext targetCtx) throws InterruptedException {
    }

    @Override
    public boolean tryAcquire() {
        return true;
    }

    // ==================== Raw data: feed to H2 decoder ====================

    @Override
    public void onData(ByteBuffer buffer, ChannelContext writeCtx, boolean isTarget, HttpProxyConnection conn) throws IOException {
        if (!isTarget) return;
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        clientReader.decode(conn.targetCtx, bytes, 0, bytes.length);
    }

    // ==================== Decoded response callback ====================

    @Override
    public void onHandle(ChannelContext ctx, Object message) throws IOException {
        Http2ClientStream clientStream = (Http2ClientStream) message;
        Http2ServerStream serverStream = serverStreamMap.remove(clientStream.getStreamId());
        if (serverStream == null) {
            log.warn("[H2H2Proxy] Unknown stream {} (may be already cleaned up)", clientStream.getStreamId());
            return;
        }
        if (serverStream.isRemoved()) {
            log.debug("[H2H2Proxy] Client stream {} already reset, discarding response", serverStream.getStreamId());
            return;
        }
        try {
            HttpDecodedResponse decodedResponse = clientReader.buildResponse(clientStream);
            Http2Helper.writeResponse(serverStream, decodedResponse);
        } catch (Exception e) {
            log.error("[H2H2Proxy] writeResponse error: {}", e.getMessage());
        }
    }
}

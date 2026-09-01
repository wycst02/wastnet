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

import io.github.wycst.wastnet.http.*;
import io.github.wycst.wastnet.http.h2.Http2ClientStream;
import io.github.wycst.wastnet.http.h2.Http2Helper;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTTP/1.1 to HTTP/2 proxy adapter.
 * <p>
 * Receives H1 requests from the client, forwards them as H2 frames
 * (HEADERS + DATA) to the target server, and converts H2 responses
 * back to H1 format for the client.
 * <p>
 * Supports h2c prior knowledge target via {@link HttpProxyConfig#h2c}.
 *
 * @author wangyc
 */
public class H1H2ProxyAdapter extends H2H2ProxyAdapter {

    private final ConcurrentHashMap<Integer, HttpDecodedRequest> requestMap =
            new ConcurrentHashMap<>();

    public H1H2ProxyAdapter(HttpProxyConnection connection) {
        super(connection);
    }

    // ==================== Request: H1 -> H2 ====================

    @Override
    public void sendRequest(HttpRequest request, ChannelContext targetCtx) throws Throwable {
        Http2ClientStream targetStream;
        boolean hasBody;
        synchronized (clientReader) {
            int targetStreamId = clientReader.nextStreamId();
            targetStream = clientReader.getOrCreateStream(targetStreamId, targetCtx);
            requestMap.put(targetStreamId, (HttpDecodedRequest) request);
            try {
                hasBody = Http2Helper.sendH2RequestHeadersFromH1(request, targetStream);
            } catch (Throwable t) {
                requestMap.remove(targetStreamId);
                log.error("[H1H2Proxy] sendRequest error: {}", t.getMessage());
                if (connection.isTargetClosed()) {
                    connection.close();
                }
                writeH1Error(connection.clientCtx, 502, "Bad Gateway");
                return;
            }
        }
        if (hasBody) {
            Http2Helper.sendH1RequestBody(request, targetStream);
        }
    }

    // ==================== Response: H2 -> H1 ====================

    @Override
    public void onHandle(ChannelContext ctx, Object message) throws IOException {
        Http2ClientStream clientStream = (Http2ClientStream) message;
        HttpDecodedRequest request = requestMap.remove(clientStream.getStreamId());
        if (request == null) {
            log.warn("[H1H2Proxy] Unknown stream {}", clientStream.getStreamId());
            return;
        }
        if (clientStream.isServerProtocolError()) {
            log.warn("[H1H2Proxy] Protocol error from target, stream {}", clientStream.getStreamId());
            writeH1Error(connection.clientCtx, 502, "Bad Gateway");
            return;
        }
        try {
            HttpDecodedResponse response = clientReader.buildResponse(clientStream);
            HttpDefaultResponse resp = request.newResponse();
            resp.status(response.getStatusCode());
            // Copy headers (skip content-length and transfer-encoding, will be set by commit)
            for (Map.Entry<String, Object> entry : response.getHeaders().entrySet()) {
                String name = entry.getKey();
                if (name.charAt(0) == ':' || HttpHeaderNames.CONTENT_LENGTH.equalsIgnoreCase(name)
                        || HttpHeaderNames.TRANSFER_ENCODING.equalsIgnoreCase(name)) continue;
                Object value = entry.getValue();
                if (value instanceof List) {
                    for (String v : (List<String>) value) {
                        resp.addHeader(name, v);
                    }
                } else if (value != null) {
                    resp.addHeader(name, String.valueOf(value));
                }
            }
            // Body
            if (response.isStream()) {
                resp.setChunkedEncoding();
                java.io.InputStream bodyStream = response.getBodyStream();
                byte[] buf = new byte[8192];
                while (bodyStream.read(buf) != -1) {
                    resp.writeChunked(buf);
                }
            } else {
                byte[] body = response.getBody();
                if (body != null && body.length > 0) {
                    resp.setContentLength(body.length);
                    resp.body(body);
                }
            }
            resp.commit();
        } catch (Exception e) {
            log.error("[H1H2Proxy] writeResponse error: {}", e.getMessage());
            writeH1Error(connection.clientCtx, 502, "Bad Gateway");
        }
    }

    private void writeH1Error(ChannelContext ctx, int code, String text) throws IOException {
        ctx.write(("HTTP/1.1 " + code + " " + text + "\r\nContent-Length: 0\r\n\r\n").getBytes());
        ctx.flush();
    }
}

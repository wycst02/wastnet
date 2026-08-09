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
package io.github.wycst.wastnet.http;

import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import io.github.wycst.wastnet.util.Utils;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * HTTP request with chunked transfer encoding.
 * <p>
 * The body is delivered incrementally via {@link HttpChunkedStream} and is NOT
 * buffered entirely in memory. Callers must consume the payload through the
 * streaming API ({@link #bodyStream()}) rather than {@link #getBodyData()}.
 *
 * @author wangyc
 */
public class HttpChunkedRequest extends HttpStreamRequest {

    private static final byte[] CRLF_BYTES = "\r\n".getBytes();
    private static final byte[] CHUNKED_END_MARKER = "0\r\n\r\n".getBytes();

    public HttpChunkedRequest(HttpMethod method, byte[] uriAsciiBytes, String requestUri, Map<String, List<String>> parameters, HttpVersion httpVersion, Map<String, Object> headers, byte[] body, long contentLength, String contentType, ChannelContext ctx) {
        super(method, uriAsciiBytes, requestUri, parameters, httpVersion, headers, body, contentLength, contentType, ctx, new HttpChunkedStream(body, ctx));
    }

    @Override
    protected void delegateBody(ChannelContext targetCtx) throws Throwable {
        byte[] buffer = new byte[8192];
        byte[] hexBuf = new byte[4]; // single chunk payload <= buffer.length (8192 = 0x2000) => at most 4 hex digits
        InputStream stream = bodyStream();
        int bytesRead;
        while ((bytesRead = stream.read(buffer)) > 0) { // >0: a data chunk; 0: terminating 0-chunk already consumed; -1: end -> append terminator below
            int hlen = Utils.intToHexBytes(bytesRead, hexBuf, 0);
            targetCtx.write(hexBuf, 0, hlen);
            targetCtx.write(CRLF_BYTES);
            targetCtx.write(buffer, 0, bytesRead);
            targetCtx.write(CRLF_BYTES);
        }
        targetCtx.write(CHUNKED_END_MARKER);
    }

    /**
     * Chunked transfer encoding streams the body incrementally and the full body
     * is not assembled in memory. Callers must read payload via the stream API
     * ({@link #bodyStream()}) instead.
     *
     * @throws IllegalStateException always thrown
     */
    @Override
    public final byte[] getBodyData() {
        throw new IllegalStateException("getBodyData() unsupported for chunked transfer encoding, use bodyStream() instead");
    }
}

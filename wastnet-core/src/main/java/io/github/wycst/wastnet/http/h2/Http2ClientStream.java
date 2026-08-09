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

import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.io.IOException;

/**
 * Client-side HTTP/2 stream context.
 * <p>
 * Handles incoming response HEADERS (parsing {@code :status}),
 * provides response body data, and manages stream cleanup.
 *
 * @author wangyc
 */
public class Http2ClientStream extends Http2Stream {

    /** Response status code parsed from {@code :status} pseudo-header */
    int statusCode;
    /** Indicates if the server sent a protocol error */
    boolean serverProtocolError;

    public Http2ClientStream(Http2MessageReader reader, int streamId, ChannelContext ctx) {
        super(reader, streamId, ctx);
    }

    @Override
    public String debugPrefix() {
        return "Client ";
    }

    @Override
    protected void onEndHeaders() {
        try {
            // Parse :status pseudo-header
            statusCode = Integer.parseInt((String) headers.get(":status"));
            // Parse content-type
            contentType = (String) headers.get("content-type");
            // Parse content-length
            String contentLengthString = (String) headers.get("content-length");
            if (contentLengthString != null) {
                declaredContentLength = Long.parseLong(contentLengthString);
            }
        } catch (NumberFormatException e) {
            serverProtocolError = true;
        }
    }

    @Override
    protected void submit() {
        requestInvoked = true;
        try {
            ctx.invokeHandle(this);
        } catch (IOException ignored) {
        }
    }

    /** Returns the response status code parsed from {@code :status} pseudo-header. */
    public int getStatusCode() {
        return statusCode;
    }

    /** Returns {@code true} if the upstream sent a malformed HEADERS block (e.g. missing {@code :status}). */
    public boolean isServerProtocolError() {
        return serverProtocolError;
    }
}

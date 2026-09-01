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

import io.github.wycst.wastnet.http.HttpMethod;
import io.github.wycst.wastnet.http.HttpOptions;
import io.github.wycst.wastnet.http.HttpStatus;
import io.github.wycst.wastnet.http.HttpUriDecoder;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Server-side HTTP/2 stream context.
 * <p>
 * Handles incoming request HEADERS (parsing {@code :method}, {@code :scheme}, {@code :path}, {@code :authority}),
 * dispatches to the application handler, and manages response stream cleanup.
 *
 * @author wangyc
 */
public class Http2ServerStream extends Http2Stream {

    /** Non-null when the request should error out instead of normal dispatch. */
    private HttpStatus errorStatus;

    // Parsed from headers at endHeaders()
    HttpMethod method;
    String scheme;
    String path;           // header: :path -> URI
    String requestUri;     // decoded URI without query string
    Map<String, List<String>> parameters;
    String authority;

    public Http2ServerStream(Http2MessageReader reader, int streamId, ChannelContext ctx) {
        super(reader, streamId, ctx);
    }

    @Override
    public String debugPrefix() {
        return "Server ";
    }

    public HttpStatus getErrorStatus() {
        return errorStatus;
    }

    /**
     * Parses and validates request pseudo-headers once the full header block is decoded.
     * Rules follow RFC 7540 §8.1.2 (mandatory pseudo-headers) and §8.3 (CONNECT).
     * A structurally malformed request throws and is reset via RST_STREAM(PROTOCOL_ERROR);
     * a well-formed but semantically invalid request (unknown method, oversized body) is
     * kept intact and answered with an HTTP status through {@code errorStatus}.
     */
    @Override
    protected void onEndHeaders() {
        // :method is mandatory (§8.1.2). An unrecognized method is not malformed, so it is
        // resolved to null below and answered with 405 instead of being reset.
        method = HttpMethod.fromString(((String) headers.get(":method")).trim());
        authority = ((String) headers.get(":authority")).trim(); // missing -> NPE -> RST_STREAM(PROTOCOL_ERROR); required for all methods (§8.1.2)
        if (method == null) {
            // Structurally valid request -> reply 405, not RST (RFC 7231 §6.5.5)
            errorStatus = HttpStatus.METHOD_NOT_ALLOWED;
        }
        // :scheme / :path are mandatory for every method except CONNECT (§8.1.2)
        scheme = ((String) headers.get(":scheme"));
        path = ((String) headers.get(":path"));
        // Optional header: content-type (null-safe, absence is allowed)
        contentType = (String) headers.get("content-type");

        // Optional header: content-length (null-safe; §8.1.2 / §8.2, validate against hard body-size limit)
        String contentLengthString = (String) headers.get("content-length");
        if (contentLengthString != null) {
            declaredContentLength = Long.parseLong(contentLengthString);
            // Body too large, exceeds hard limit
            if (declaredContentLength > ctx.option(HttpOptions.BODY_MAX_SIZE)) {
                errorStatus = HttpStatus.REQUEST_ENTITY_TOO_LARGE;
            }
        }

        if (authority.isEmpty()) { // required for all methods (RFC 7540 §8.1.2)
            throw new IllegalArgumentException("Malformed request: illegal or missing pseudo-headers");
        }
        // CONNECT carries no :scheme/:path, so only non-CONNECT validates them (RFC 7540 §8.1.2)
        if (method != HttpMethod.CONNECT) {
            if ((scheme = scheme.trim()).isEmpty() || (path = path.trim()).isEmpty()) {
                throw new IllegalArgumentException("Malformed request: illegal or missing pseudo-headers");
            }
            if (path.charAt(0) != '/') {
                if (method != HttpMethod.OPTIONS || !path.equals("*")) {
                    throw new IllegalArgumentException("Malformed request: illegal or missing pseudo-headers");
                }
                // RFC 7230 asterisk-form: a valid request target, handed to the application layer
            }
        }

        // path/scheme may be null (CONNECT / malformed request): NPE here closes the stream via onHeadersFrame() -> RST_STREAM + removeStream
        HttpUriDecoder uriDecoder = new HttpUriDecoder(false);
        uriDecoder.codec(path.getBytes());
        uriDecoder.endCodec();
        requestUri = uriDecoder.getUri();
        parameters = uriDecoder.getParameters();
    }

    @Override
    protected void submit() {
        final Http2Request request = new Http2Request(this).errorStatus(errorStatus);
        requestInvoked = true;
        H2Monitor.incrSubmitRequests();
        ctx.runAsync(() -> {
            try {
                ctx.invokeHandle(request);
            } catch (IOException e) {
                log.error("Invoke handle error, streamId=" + streamId, e);
            } finally {
                if (!handovered) {
                    completeStream();
                }
            }
        });
    }

    /**
     * Complete this server-side stream. Counts one completed response here (instead of in
     * {@code submit()}) so that handed-over streams (proxy forwarding) are also counted.
     */
    @Override
    void completeStream() {
        H2Monitor.incrTotalResponses(createdAt);
        super.completeStream();
    }

}

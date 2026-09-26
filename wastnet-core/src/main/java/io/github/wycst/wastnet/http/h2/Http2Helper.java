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

import io.github.wycst.wastnet.http.*;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import io.github.wycst.wastnet.util.Utils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

/**
 * Static utility helpers for HTTP/2 stream processing.
 * <p>
 * Provides H2&rarr;H1 proxy request building, H2 response frame writing from
 * decoded H1 responses, and HPACK header encoding utilities shared with
 * {@link Http2Response}.
 *
 * @author wangyc
 */
public final class Http2Helper {

    private static final byte[] COLON_SPACE_BYTES = ": ".getBytes();
    private static final byte[] CRLF_BYTES = "\r\n".getBytes();
    private static final byte[] H1_VERSION_CRLF = " HTTP/1.1\r\n".getBytes();

    /** Safe upper bound for the HPACK string length-prefix byte count (always ≤ 5). */
    private static final int HPACK_LEN_PREFIX_BUDGET = 5;

    // Pre-encoded HPACK literal for ":status: 502" (never-indexed, ref static index 8)
    // Hex: 18 03 35 30 32
    private static final byte[] H2_ERROR_502_HEADER_PAYLOAD = {
        0x18, 0x03, 0x35, 0x30, 0x32
    };

    private Http2Helper() {
    }

    /**
     * Build and send an HTTP/1.1 request to the target server, using the
     * internal headers map for efficient traversal (avoids HttpRequest wrappers).
     */
    public static void sendH1Request(Http2ServerStream streamCtx, ChannelContext targetCtx) throws IOException {
        String reqUri = streamCtx.path;
        if (reqUri == null || reqUri.isEmpty()) reqUri = "/";

        HttpBuf buf = HttpBuf.of(512);
        buf.write(streamCtx.method.name().getBytes());
        buf.write((byte) ' ');
        buf.write(reqUri.getBytes());
        buf.write(H1_VERSION_CRLF);

        // Iterate internal headers map directly, skip pseudo, content-length, host, transfer-encoding
        boolean writeHost = false;
        for (Map.Entry<String, Object> entry : streamCtx.headers.entrySet()) {
            String name = entry.getKey();
            Object value = entry.getValue();
            if (name.charAt(0) == ':'
                    || HttpHeaderNames.CONTENT_LENGTH.equals(name)
                    || HttpHeaderNames.TRANSFER_ENCODING.equals(name)) continue;
            if (HttpHeaderNames.HOST.equals(name)) {
                writeHost = true;
            }
            if (value instanceof List) {
                for (String v : (List<String>) value) {
                    writeH1HeaderLine(buf, name, v);
                }
            } else if (value != null) {
                writeH1HeaderLine(buf, name, String.valueOf(value));
            }
        }

        // :authority → H1 Host
        if (streamCtx.authority != null && !writeHost) {
            writeH1HeaderLine(buf, HttpHeaderNames.HOST, streamCtx.authority);
        }

        boolean isStream = streamCtx.needStreaming;
        if (!isStream) {
            long contentLengthVal = streamCtx.bodyData.length;
            if (contentLengthVal > 0) {
                writeH1HeaderLine(buf, HttpHeaderNames.CONTENT_LENGTH, String.valueOf(contentLengthVal));
            }
            buf.write(CRLF_BYTES);
            targetCtx.write(buf.byteBuffer());
            if (contentLengthVal > 0) {
                targetCtx.write(streamCtx.bodyData);
            }
            targetCtx.flush();
        } else {
            writeH1HeaderLine(buf, HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
            buf.write(CRLF_BYTES);
            targetCtx.write(buf.byteBuffer());
            InputStream bodyStream = streamCtx.getInputStream();
            byte[] chunkBuf = new byte[8192];
            int n;
            while ((n = bodyStream.read(chunkBuf)) != -1) {
                writeH1Chunk(targetCtx, chunkBuf, 0, n);
            }
            targetCtx.write("0\r\n\r\n".getBytes());
            targetCtx.flush();
        }
    }

    /**
     * Convert a decoded H1 response to H2 frames and write to client.
     * <p>
     * Body data is streamed in fixed-size DATA frames (up to 16KB each),
     * regardless of whether the original response was chunked/streaming/normal.
     * Ends with an empty DATA frame carrying END_STREAM to terminate the stream.
     *
     * @param streamCtx the H2 stream context
     * @param response  the decoded H1 response
     * @throws IOException if write to client fails
     */
    public static void writeResponse(Http2Stream streamCtx, HttpDecodedResponse response) throws IOException {
        try {
            byte[] headerPayload = encodeHpackHeaders(response);
            boolean hasBody = response.getContentLength() > 0 || response.isStream();
            streamCtx.writeHeadersFrame(headerPayload, !hasBody); // HEADERS frame (END_HEADERS set; END_STREAM only if no body)
            if (!hasBody) return;
            // Unified body reading: stream response → bodyStream, otherwise wrap byte[]
            InputStream in = response.isStream() ? response.getBodyStream()
                    : new ByteArrayInputStream(response.getBody());

            int chunkSize = streamCtx.sendChunkSize();
            byte[] frameBuf = new byte[chunkSize];
            int n;
            while ((n = in.read(frameBuf)) != -1) {
                ByteBuffer dataFrame = streamCtx.createFrameBuffer(9 + n, n, Http2Frame.FRAME_TYPE_DATA, 0);
                dataFrame.put(frameBuf, 0, n);
                dataFrame.flip();
                streamCtx.writeDataFrame(dataFrame, n);
            }
        } finally {
            streamCtx.completeStream();
        }
    }

    /**
     * Send a gateway error (502) to the H2 client using pre-cached H2 frames.
     * Avoids HPACK re-encoding overhead of {@link #writeResponse(Http2Stream, HttpDecodedResponse)}.
     */
    public static void sendGatewayError(Http2Stream streamCtx) throws IOException {
        int hFlags = Http2Frame.END_HEADERS | Http2Frame.END_STREAM;
        ByteBuffer headerFrame = streamCtx.createFrameBuffer(9 + H2_ERROR_502_HEADER_PAYLOAD.length,
                H2_ERROR_502_HEADER_PAYLOAD.length, Http2Frame.FRAME_TYPE_HEADERS, hFlags);
        headerFrame.put(H2_ERROR_502_HEADER_PAYLOAD);
        headerFrame.flip();
        streamCtx.writeFrame(headerFrame);
        streamCtx.completeStream();
    }

    // ==================== H2 request encoding and sending (proxy) ====================

    /**
     * HPACK-encode H2 request headers from a server-side H2 stream for proxy forwarding
     * to an H2 target server.
     * <p>
     * Encodes pseudo-headers ({@code :method}, {@code :scheme}, {@code :path},
     * {@code :authority}) followed by regular headers. Content-length is omitted
     * since H2 framing handles body delimitation.
     *
     * @param stream the server-side H2 stream containing the original request headers
     * @return HPACK-encoded header payload ready for a HEADERS frame
     */
    public static byte[] encodeH2RequestHeaders(Http2ServerStream stream) {
        HttpBuf buf = HttpBuf.of(256);
        // :authority - prefer host header rewritten by proxy config
        String authority = stream.authority;
        String hostHeader = (String) stream.headers.get(HttpHeaderNames.HOST);
        if (hostHeader != null) authority = hostHeader;
        writeH2PseudoHeaders(buf, stream.method, stream.path, stream.scheme, authority);
        writeHpackHeaders(stream.headers, buf);
        return buf.toBytes();
    }

    /**
     * HPACK-encode HTTP/2 pseudo-headers (:method, :path, :scheme, :authority).
     */
    private static void writeH2PseudoHeaders(HttpBuf buf, HttpMethod method, String path, String scheme, String authority) {
        // :method - indexed (idx 2=GET, 3=POST) or name-ref (idx 2)
        if (method == HttpMethod.GET) {
            buf.write((byte) 0x82);                                   // A, :method GET (idx 2)
        } else if (method == HttpMethod.POST) {
            buf.write((byte) 0x83);                                   // A, :method POST (idx 3)
        } else {
            buf.write((byte) 0x12);                                   // D, name=:method (idx 2)
            writeHpackString(buf, method.name());
        }
        // :scheme - indexed (idx 6=http, 7=https) or name-ref (idx 6)
        if ("https".equals(scheme)) {
            buf.write((byte) 0x87);                                   // A, :scheme https (idx 7)
        } else {
            buf.write((byte) 0x86);                                   // A, :scheme http (idx 6)
        }
        // :path - indexed (idx 4=/) or name-ref (idx 4)
        if ("/".equals(path)) {
            buf.write((byte) 0x84);                                   // A, :path / (idx 4)
        } else if ("/index.html".equals(path)) {
            buf.write((byte) 0x85);                                   // A, :path /index.html (idx 5)
        } else {
            buf.write((byte) 0x14);                                   // D, name=:path (idx 4)
            writeHpackString(buf, path);
        }
        // :authority - name-ref (idx 1)
        if (authority != null) {
            buf.write((byte) 0x11);                                   // D, name=:authority (idx 1)
            writeHpackString(buf, authority);
        }
    }

    /**
     * Encode and send H2 HEADERS frame to the target server.
     * Returns true if there is body data to send subsequently.
     */
    public static boolean sendH2RequestHeaders(Http2ServerStream serverStream, Http2ClientStream targetStream) throws IOException {
        byte[] headerPayload = encodeH2RequestHeaders(serverStream);
        boolean hasBody = serverStream.bodyData.length > 0;
        targetStream.writeHeadersFrame(headerPayload, !hasBody);
        return hasBody;
    }

    /**
     * Send H2 request body DATA frames to the target server,
     * after {@link #sendH2RequestHeaders} has been called.
     */
    public static void sendH2RequestBody(Http2ServerStream serverStream, Http2ClientStream targetStream) throws IOException {
        if (!serverStream.needStreaming) {
            // In-memory body (guaranteed non-empty by caller)
            sendDataFrames(targetStream, serverStream.bodyData, 0, serverStream.bodyData.length);
        } else {
            // Streaming body (e.g. large uploads)
            InputStream bodyStream = serverStream.getInputStream();
            byte[] chunkBuf = new byte[targetStream.sendChunkSize()];
            int n;
            while ((n = bodyStream.read(chunkBuf)) != -1) {
                ByteBuffer frame = targetStream.createFrameBuffer(9 + n, n, Http2Frame.FRAME_TYPE_DATA, 0);
                frame.put(chunkBuf, 0, n);
                frame.flip();
                targetStream.writeDataFrame(frame, n);
            }
            sendEmptyEndStream(targetStream);
        }
    }

    /**
     * Send body data as one or more DATA frames, with END_STREAM on the final frame.
     */
    private static void sendDataFrames(Http2Stream stream, byte[] data, int offset, int len) throws IOException {
        int chunkSize = stream.sendChunkSize();
        int remaining = len;
        int off = offset;
        while (remaining > 0) {
            int chunkLen = Math.min(remaining, chunkSize);
            boolean last = remaining <= chunkLen;
            int flags = last ? Http2Frame.END_STREAM : 0;
            ByteBuffer frame = stream.createFrameBuffer(9 + chunkLen, chunkLen, Http2Frame.FRAME_TYPE_DATA, flags);
            frame.put(data, off, chunkLen);
            frame.flip();
            stream.writeDataFrame(frame, chunkLen);
            off += chunkLen;
            remaining -= chunkLen;
        }
    }

    /**
     * Send an empty DATA frame with END_STREAM to terminate the stream.
     */
    private static void sendEmptyEndStream(Http2Stream stream) throws IOException {
        ByteBuffer endFrame = stream.createFrameBuffer(9, 0, Http2Frame.FRAME_TYPE_DATA, Http2Frame.END_STREAM);
        endFrame.flip();
        stream.writeFrame(endFrame);
    }

    // ==================== H1 → H2 request forwarding ====================

    /**
     * Send an H1 request as H2 HEADERS + DATA frames on the given target stream.
     * <p>
     * HPACK-encodes pseudo-headers ({@code :method}, {@code :scheme}, {@code :path}, {@code :authority})
     * from the H1 request, followed by regular headers (skipping H2-invalid ones).
     *
     * @param request      the original H1 request
     * @param targetStream the target H2 stream to send frames on
     * @return true if the request has a body (caller must send body frames separately)
     * @throws IOException if frame writing fails
     */
    public static boolean sendH2RequestHeadersFromH1(HttpRequest request, Http2ClientStream targetStream) throws IOException {
        HttpBuf buf = HttpBuf.of(256);
        String uri = request.getUri();
        String path = uri;
        String authority = request.getHeader(HttpHeaderNames.HOST);
        // Default scheme from inbound connection; absolute-form URIs override per RFC 7540 §8.1.2.3.
        String scheme = request.getScheme();
        if (uri.startsWith("http")) {
            try {
                URI parsed = URI.create(uri);
                if (parsed.getHost() != null) {            // confirms it is a real absolute-form
                    String rawPath = parsed.getRawPath();
                    String rawQuery = parsed.getRawQuery();
                    path = (rawPath == null || rawPath.isEmpty()) ? "/" : rawPath;
                    if (rawQuery != null) {
                        path = path + "?" + rawQuery;
                    }
                    authority = parsed.getAuthority();
                    scheme = parsed.getScheme();
                }
            } catch (IllegalArgumentException ignored) {
                // malformed absolute URI: leave as-is; H2 peer will reject the invalid :path
            }
        }
        writeH2PseudoHeaders(buf, request.getMethod(), path,
                scheme, authority);

        // Regular headers (skip H2-invalid ones)
        for (String name : request.getHeaderNames()) {
            String lower = name.toLowerCase();
            if (isH2ForbiddenHeader(lower, true)) continue;
            // Preserve multi-value headers: write each value as a separate HPACK literal.
            // getHeader(name) returns only the first value and would drop duplicates.
            Object raw = request.getRawHeader(name);
            if (raw == null) continue;
            if (raw instanceof List) {
                for (String v : (List<String>) raw) {
                    writeHpackLiteral(buf, lower, v);
                }
            } else {
                writeHpackLiteral(buf, lower, String.valueOf(raw));
            }
        }

        byte[] headerPayload = buf.toBytes();
        boolean hasBody = request.getContentLength() > 0 || request.isStream();
        targetStream.writeHeadersFrame(headerPayload, !hasBody);
        return hasBody;
    }

    /**
     * Send H1 request body as H2 DATA frames on the given target stream.
     * Handles both in-memory and streaming bodies.
     *
     * @param request      the original H1 request
     * @param targetStream the target H2 stream
     * @throws IOException if frame writing fails
     */
    public static void sendH1RequestBody(HttpRequest request, Http2ClientStream targetStream) throws IOException {
        if (request.isStream()) {
            // Streaming request: read via bodyStream(), getBodyData() throws in streaming mode
            InputStream bodyStream = request.bodyStream();
            int chunkSize = targetStream.sendChunkSize();
            byte[] buf = new byte[chunkSize];
            int n;
            while ((n = bodyStream.read(buf)) != -1) {
                ByteBuffer frame = targetStream.createFrameBuffer(9 + n, n, Http2Frame.FRAME_TYPE_DATA, 0);
                frame.put(buf, 0, n);
                frame.flip();
                targetStream.writeDataFrame(frame, n);
            }
            sendEmptyEndStream(targetStream);
        } else {
            byte[] bodyData = request.getBodyData();
            if (bodyData.length > 0) {
                sendDataFrames(targetStream, bodyData, 0, bodyData.length);
            } else {
                // content-length > 0 but no actual body: terminate the stream explicitly
                sendEmptyEndStream(targetStream);
            }
        }
    }

    // ==================== HPACK response encoding ====================

    /**
     * Write headers to HPACK-encoded literals, skipping headers invalid in HTTP/2:
     * pseudo-headers ({@code :} prefix), {@code content-length} and the connection-specific
     * header fields forbidden by RFC 7540 §8.1.2.2 ({@code transfer-encoding},
     * {@code connection}, {@code keep-alive}, {@code proxy-connection}, {@code upgrade}).
     * <p>
     * List values are unwrapped and each value is written as a separate header line.
     */
    private static void writeHpackHeaders(Map<String, Object> headers, HttpBuf buf) {
        for (Map.Entry<String, Object> entry : headers.entrySet()) {
            String name = entry.getKey().toLowerCase();
            if (isH2ForbiddenHeader(name, false)) continue;
            Object value = entry.getValue();
            if (value instanceof List) {
                for (String v : (List<String>) value) {
                    writeHpackLiteral(buf, name, v);
                }
            } else if (value != null) {
                writeHpackLiteral(buf, name, String.valueOf(value));
            }
        }
    }

    /**
     * Whether the given lower-cased header name must be skipped in HTTP/2 header
     * encoding. Pseudo-headers (starting with ':') and H1-only connection headers
     * are never valid in HTTP/2. When skipHost is true the host header is also
     * skipped (it maps to the :authority pseudo-header for requests).
     *
     * @param lowerName lower-cased header name
     * @param skipHost  true to also skip the host header
     * @return true if the header should be skipped
     */
    private static boolean isH2ForbiddenHeader(String lowerName, boolean skipHost) {
        return lowerName.charAt(0) == ':'
                || HttpHeaderNames.CONTENT_LENGTH.equals(lowerName)
                || HttpHeaderNames.TRANSFER_ENCODING.equals(lowerName)
                || HttpHeaderNames.CONNECTION.equals(lowerName)
                || HttpHeaderNames.KEEP_ALIVE.equals(lowerName)
                || HttpHeaderNames.PROXY_CONNECTION.equals(lowerName)
                || HttpHeaderNames.UPGRADE.equals(lowerName)
                || (skipHost && HttpHeaderNames.HOST.equals(lowerName));
    }

    /**
     * HPACK encode response headers (status + regular headers).
     * Skips H1-specific headers (transfer-encoding, content-length, connection, keep-alive)
     * that are not valid in HTTP/2.
     */
    public static byte[] encodeHpackHeaders(HttpDecodedResponse response) {
        HttpBuf buf = HttpBuf.of(256);
        writeHpackStatus(buf, response.getStatusCode()); // :status pseudo-header
        Map<String, Object> headers = response.getHeaders(); // Regular headers, skip H1-specific headers
        if (headers != null) {
            writeHpackHeaders(headers, buf);
        }

        return buf.toBytes();
    }

    // ==================== HPACK utility (shared with Http2Response) ====================

    /**
     * Write HPACK-encoded :status pseudo-header.
     * <p>
     * Uses single-byte Indexed Header Field (A) for 200 and 500,
     * falls back to Never Indexed (D) + inline value for other codes.
     * <p>
     * Note: caller must ensure buf has enough free space (1 byte).
     *
     * @param buf        the target buffer
     * @param statusCode the HTTP status code
     */
    public static void writeHpackStatus(HttpBuf buf, int statusCode) {
        if (statusCode == 200) {
            buf.writeUnchecked((byte) (0x80 | 8));   // A, :status 200 (idx 8)
        } else if (statusCode == 500) {
            buf.writeUnchecked((byte) (0x80 | 14));  // A, :status 500 (idx 14)
        } else {
            buf.writeUnchecked((byte) (0x10 | 8));   // D, name=:status (idx 8, ≤15)
            writeHpackString(buf, String.valueOf(statusCode));
        }
    }

    /**
     * Write a never-indexed literal HPACK header field (name + value) into the buffer.
     * Emits the 0x10 literal flag followed by the HPACK-encoded name and value strings.
     *
     * @param buf   the target buffer
     * @param name  the header name
     * @param value the header value
     */
    public static void writeHpackLiteral(HttpBuf buf, String name, String value) {
        buf.write((byte) 0x10); // never-indexed literal header field
        writeHpackString(buf, name);
        writeHpackString(buf, value);
    }

    /**
     * Encodes a string as an HPACK string literal (length-prefixed, optional Huffman).
     * Huffman is used only for ASCII-only strings longer than 5 bytes when
     * {@link HttpConf#HTTP2_HPACK_HUFFMAN_ENABLED} is on.
     *
     * @param buf   the target buffer
     * @param value the string value to encode
     */
    public static void writeHpackString(HttpBuf buf, String value) {
        byte[] bytes = value.getBytes(Utils.UTF_8);
        // ASCII-only (bytes.length == value.length()) is the Huffman candidate:
        // multibyte UTF-8 is left uncompressed to avoid the per-string encode cost.
        writeHpackBytes(buf, bytes, bytes.length == value.length());
    }

    /**
     * Writes a byte slice as an HPACK string literal, directly into the buffer
     * (no scratch array). Honors Huffman only when enabled and {@code len > 5}.
     *
     * @param buf         target buffer
     * @param bytes       literal octets
     * @param offset      start offset within {@code bytes}
     * @param len         number of bytes to take
     * @param useHuffman  Huffman candidate
     */
    public static void writeHpackBytes(HttpBuf buf, byte[] bytes, int offset, int len, boolean useHuffman) {
        // Gate on the global switch and a length floor (Huffman expands short strings).
        useHuffman = useHuffman && HttpConf.HTTP2_HPACK_HUFFMAN_ENABLED && len > 5;

        int dataLen = useHuffman
                ? HuffmanByteCodec.computeHuffmanLength(bytes, offset, len)
                : len;

        buf.incrementCapacity(HPACK_LEN_PREFIX_BUDGET + dataLen);
        byte[] dst = buf.getBuf();
        int off = buf.getWriteIndex();
        int prefixLen = Http2HpackCodec.encodeLength(dataLen, dst, off, useHuffman);

        // Huffman-encoded in place, or copied verbatim behind the prefix.
        if (useHuffman) {
            HuffmanByteCodec.encodeData(bytes, offset, len, dst, off + prefixLen);
        } else {
            System.arraycopy(bytes, offset, dst, off + prefixLen, len);
        }
        buf.setCount(buf.size() + prefixLen + dataLen);
    }

    /**
     * Convenience overload: writes the whole {@code bytes} array as an HPACK string literal.
     *
     * @see #writeHpackBytes(HttpBuf, byte[], int, int, boolean)
     */
    public static void writeHpackBytes(HttpBuf buf, byte[] bytes, boolean useHuffman) {
        writeHpackBytes(buf, bytes, 0, bytes.length, useHuffman);
    }

    private static void writeH1HeaderLine(HttpBuf buf, String name, String value) {
        buf.write(name.getBytes());
        buf.write(COLON_SPACE_BYTES);
        buf.write(value.getBytes());
        buf.write(CRLF_BYTES);
    }

    private static void writeH1Chunk(ChannelContext ctx, byte[] data, int offset, int len) throws IOException {
        byte[] hexBuf = new byte[8];
        ctx.write(hexBuf, 0, Utils.intToHexBytes(len, hexBuf, 0));
        ctx.write(CRLF_BYTES);
        ctx.write(data, offset, len);
        ctx.write(CRLF_BYTES);
    }
}

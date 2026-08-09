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

import io.github.wycst.wastnet.log.Log;
import io.github.wycst.wastnet.log.LogFactory;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import io.github.wycst.wastnet.util.Utils;

import java.io.IOException;
import java.util.*;

/**
 * HTTP/1.x request parser. Non-thread-safe, one connection one decoder.
 *
 * @author wangyc
 * @since 2024-1-28
 */
public class HttpRequestDecoder extends HttpMessageDecoder {

    static final Log log = LogFactory.getLog(HttpRequestDecoder.class);
    static final byte HEADER_KEY = 0;
    static final byte HEADER_VALUE = 1;
    static final byte BODY_MODE_NORMAL = 0;
    static final byte BODY_MODE_STREAM = 1;
    static final byte BODY_MODE_CHUNKED = 2;

    // Last-2-char short dispatch keys (lowercase, collation-safe with equals guard)
    private static final short K_CONTENT_TYPE   = (short)('p' << 8 | 'e');
    private static final short K_CONTENT_LENGTH = (short)('t' << 8 | 'h');
    private static final short K_HOST           = (short)('s' << 8 | 't');
    private static final short K_EXPECT         = (short)('c' << 8 | 't');
    private static final short K_TRANSFER_ENCODING = (short)('n' << 8 | 'g');
    private static final byte[] CONTINUE_RESPONSE = "HTTP/1.1 100 Continue\r\n\r\n".getBytes(Utils.ISO_8859_1);

    final ChannelContext ctx;
    final HttpUriDecoder uriDecoder = new HttpUriDecoder(false);
    private ReadState readState = ReadState.Init;
    final String[] startLineValues = new String[3];
    byte[] startLineMiddle;
    private int startLineIdx;
    private final HttpBuf httpBuf = HttpBuf.of(256);
    private String headerKey;
    private String headerValue;
    byte[] body = HttpRequest.EMPTY_BODY;
    private byte headerState;
    long contentLength;
    private boolean hasContentLength;
    private boolean hasHost; // Host header present
    byte bodyMode;
    HttpStatus status;
    private int totalHeaderSize; // Accumulated total size of all headers
    private boolean expectLF; // Expecting \n after \r in boundary
    private boolean expectContinue; // Expect: 100-continue detected
    private boolean chunked; // Transfer-Encoding: chunked detected
    private long requestStartTime; // Request decode start time for timeout check
    final Map<String, Object> headers = HttpConf.PRESERVE_HEADER_ORDER ? new LinkedHashMap<String, Object>() : new HashMap<String, Object>();
    String contentType;
    boolean shallow;

    /** No-arg constructor, ctx must be set externally before use. */
    public HttpRequestDecoder() {
        this(null);
    }

    /** @param ctx the channel context for this connection (may be null if set later) */
    public HttpRequestDecoder(ChannelContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Set shallow mode.
     *
     * @param shallow when true, skip URI decode, header length validation, pipeline handling
     */
    public void setShallow(boolean shallow) {
        this.shallow = shallow;
    }

    public void decode(byte[] buf, int offset, int len, ChannelContext ctx) throws IOException {
        if (len == 0) return;
        boolean completed = false;
        final int endIndex = offset + len;
        while (true) {
            int next = endIndex;
            try {
                next = decode(buf, offset, len);
                if (handleBadOrTimeout()) return;
            } catch (Throwable throwable) {
                onException(ctx, throwable);
                return;
            }
            if (readState == ReadState.Completed) {
                completed = true;
                try {
                    onDecoded(ctx);
                } finally {
                    reset();
                }
                if (next < endIndex) {
                    len -= next - offset;
                    offset = next;
                    continue;
                }
            }
            break;
        }
        if (completed) ctx.flush();
    }

    /**
     * Check for request timeout or decode error, invoke onBadDecoded and reset if needed.
     *
     * @return true if a bad request was handled and outer decode should return early
     * @throws IOException if handler execution fails
     */
    private boolean handleBadOrTimeout() throws IOException {
        if(shallow) return false;
        if (readState != ReadState.Completed
                && System.currentTimeMillis() - requestStartTime > HttpConf.REQUEST_TIMEOUT_MS) {
            status = HttpStatus.REQUEST_TIMEOUT;
            readState = ReadState.Completed;
        }
        if (status != null) {
            headers.put(HttpHeaderNormalized.getConnection(), HttpHeaderValues.CLOSE); // any decoding error may leave residual bytes in buffer, force close to avoid client hang
            onBadDecoded(ctx, status);
            ctx.flush();
            reset();
            return true;
        }
        return false;
    }

    public int decode(byte[] buf, int offset, int len) {
        int limit = offset + len;
        switch (readState) {
            case Init:
                return readStartLine(buf, offset, limit);
            case ReadHeader:
                return readHeaders(buf, offset, limit);
            case ReadBoundary:
                return readBoundary(buf, offset, limit);
            case ReadBody:
                return readBody(buf, offset, limit);
            default:
                return limit;
        }
    }

    public HttpMessage getResult() {
        if (status == null && readState == ReadState.Completed) {
            return new HttpDefaultRequest(HttpMethod.fromString(startLineValues[0]), startLineMiddle, uriDecoder.getUri(), uriDecoder.getParameters(), HttpVersion.of(startLineValues[2]), headers, body, contentLength, contentType);
        }
        return new HttpBadRequest(HttpMethod.fromString(startLineValues[0]), startLineMiddle, uriDecoder.getUri(), uriDecoder.getParameters(), HttpVersion.of(startLineValues[2]), headers, body, contentLength, contentType, null).status(status == null ? HttpStatus.BAD_REQUEST : status);
    }

    protected void onDecodeUri(byte[] secondTokenBytes) {
        if (!shallow) {
            uriDecoder.codec(secondTokenBytes).endCodec();
        } else {
            uriDecoder.setRawUri(HttpUnsafe.createAsciiString(secondTokenBytes));
        }
    }

    /** Log reason and close connection on protocol violation. */
    private void protocolClose(String reason) {
        log.debug("Protocol violation, closing: {}", reason);
        if (ctx != null) ctx.close();
    }

    /**
     * Parse the first line (request-line or status-line) of an HTTP/1.x message.
     * <p>
     * Supports two first-line formats:
     * <pre>
     * Request-line:  {@code GET /path HTTP/1.1\r\n}
     * Status-line:   {@code HTTP/1.1 200 OK\r\n}
     * </pre>
     * Both consist of three space-separated tokens terminated by CRLF.
     * They are parsed into the following internal fields:
     * <pre>
     * Token index | Request (pci=0,1,2)     | Response (pci=0,1,2)
     * ------------|-------------------------|-------------------------
     * rpc[0]      | HTTP method (e.g. GET)  | HTTP version (e.g. HTTP/1.1)
     * secondTokenBytes / rpc[1] | URI path (e.g. /index.html) | Status code bytes (e.g. "200")
     * rpc[2]      | HTTP version            | Reason phrase (e.g. "OK")
     * </pre>
     * The second token (pci==1) is stored as raw ASCII bytes in {@link #startLineMiddle}
     * and decoded through {@link #uriDecoder} (request) or used directly as status code
     * digits (response). The first and third tokens are stored as strings in {@link #startLineValues}.
     * </p>
     * <p>
     * On success, advances {@link #readState} to {@link ReadState#ReadHeader}
     * and falls through to {@link #readHeaders}. On failure or insufficient data,
     * sets {@link #status} to the appropriate error code and returns early.
     * </p>
     *
     * @param buf    the byte array to parse
     * @param offset start position in the array (inclusive)
     * @param limit  end position in the array (exclusive)
     */
    protected int readStartLine(byte[] buf, int offset, int limit) {
        if (requestStartTime == 0) requestStartTime = System.currentTimeMillis();
        byte b = '\0';
        while (true) {
            int begin = offset, limit8 = limit - 8;
            boolean matched = false;
            if (offset <= limit8) {
                do {
                    long mask = HttpUnsafe.maskOfWhitespace(buf, offset);
                    if (mask != 0) {
                        offset += HttpUnsafe.offsetTokenBytes(mask);
                        if((b = buf[offset]) < 0) {
                            ++offset;  // support negative bytes
                            continue;
                        }
                        matched = true;
                        httpBuf.write(buf, begin, offset - begin);
                        break;
                    }
                    offset += 8;
                    if (offset > limit8) {
                        httpBuf.write(buf, begin, offset - begin);
                        break;
                    }
                } while (true);
            }
            if (!matched) {
                while (offset < limit && (b = buf[offset]) > ' ') {
                    httpBuf.write(b);
                    ++offset;
                }
            }
            if (offset == limit) {
                if (httpBuf.size() > HttpConf.MAX_URI_LENGTH) status = HttpStatus.REQUEST_URI_TOO_LONG;
                return limit;
            }
            if (b == '\n' && httpBuf.isEmpty()) {
                if (startLineIdx != 3) {
                    protocolClose("unexpected newline");
                    return limit;
                }
                break;
            }
            if (startLineIdx > 2) {
                protocolClose("too many tokens");
                return limit;
            }
            if (startLineIdx != 1) {
                startLineValues[startLineIdx++] = httpBuf.toISO_8859_1_string();
                httpBuf.clear();
            } else {
                onDecodeUri(startLineMiddle = httpBuf.toBytes());
                httpBuf.clear();
                if (startLineMiddle.length > HttpConf.MAX_URI_LENGTH) {
                    status = HttpStatus.REQUEST_URI_TOO_LONG;
                    return limit;
                }
                ++startLineIdx;
            }
            if (b == ' ' || (b != '\r' && b != '\n')) { // SP is high-frequency, single compare via short-circuit optimization
                while (++offset < limit && (b = buf[offset]) <= ' ') {
                    if (b == '\r' || b == '\n') break;
                }
                if (b > ' ') {
                    httpBuf.write(b);
                    ++offset;
                    continue;
                }
            }
            if (b == '\r') {
                if (++offset >= limit) return limit;
                b = buf[offset];
            }
            if (b == '\n') {
                if (startLineIdx != 3) {
                    protocolClose("invalid request line");
                    return limit;
                }
                break;
            }
        }
        readState = ReadState.ReadHeader;
        return readHeaders(buf, ++offset, limit);
    }

    int readHeaders(byte[] buf, int offset, int limit) {
        // Fast path: detect header terminator on entry (re-entry after buffer boundary)
        if (httpBuf.isEmpty() && offset < limit) {
            byte b = buf[offset];
            if (b == '\r' || b == '\n') {
                readState = ReadState.ReadBoundary;
                return readBoundary(buf, offset, limit);
            }
        }
        for (; ; ) {
            if (headerState == HEADER_KEY) {
                offset = readHeaderKey(buf, offset, limit);
                if (offset == limit) {
                    if (httpBuf.size() > HttpConf.MAX_HTTP_HEADER_SIZE) status = HttpStatus.REQUEST_HEADER_FIELDS_TOO_LARGE;
                    return limit;
                }
                ++offset;
                headerState = HEADER_VALUE;
            }
            offset = readHeaderValue(buf, offset, limit);
            if (offset == limit) {
                if (httpBuf.size() > HttpConf.MAX_HTTP_HEADER_SIZE) status = HttpStatus.REQUEST_HEADER_FIELDS_TOO_LARGE;
                return limit;
            }
            addHeader(headerKey, headerValue);
            headerState = HEADER_KEY;
            if (++offset == limit) return limit;
            byte b = buf[offset];
            if (b == '\r' || b == '\n') {
                readState = ReadState.ReadBoundary;
                return readBoundary(buf, offset, limit);
            }
        }
    }

    protected void prepareRequestContent() {
        if(shallow) return;
        if (expectContinue && status == null && ctx != null) {
            ctx.writeFlushWithoutThrow(CONTINUE_RESPONSE);
            expectContinue = false;
        }
    }

    int readHeaderKey(byte[] buf, int offset, int limit) {
        final int begin = offset;
        if (httpBuf.isEmpty() && offset < limit - 24) {
            quick:
            {
                for (int step = 0; step <= 16; step += 8) {
                    long mask = HttpUnsafe.maskOfColon(HttpUnsafe.getLong(buf, offset + step));
                    if (mask != 0) {
                        int len = step + HttpUnsafe.offsetTokenBytes(mask), tarOff = offset + len;
                        if (buf[tarOff] == ':') {
                            byte[] target = new byte[len];
                            System.arraycopy(buf, begin, target, 0, len);
                            HttpHeaderUtils.normalizedKeyBuffer(target, 0, len);
                            headerKey = HttpUnsafe.createAsciiString(target);
                            return tarOff;
                        }
                        break quick;
                    }
                }
                offset += 24;
            }
        }
        while (offset < limit && buf[offset] != ':') ++offset;
        if (offset == limit) {
            httpBuf.write(buf, begin, offset - begin);
            return offset;
        }
        if (offset != begin) httpBuf.write(buf, begin, offset - begin);
        HttpHeaderUtils.normalizedKeyBuffer(httpBuf.buf, httpBuf.begin, httpBuf.count);
        headerKey = httpBuf.toISO_8859_1_string();
        httpBuf.clear();
        return offset;
    }

    int readHeaderValue(byte[] buf, int offset, int limit) {
        int begin = offset;
        final int limit8 = limit - 8;
        boolean matched = false;
        while (offset <= limit8) {
            long mask = HttpUnsafe.maskOfNewline(HttpUnsafe.getLong(buf, offset));
            if (mask != 0) {
                offset += HttpUnsafe.offsetTokenBytes(mask);
                if (buf[offset] == '\n') {
                    matched = true;
                    break;
                }
                ++offset;
            } else offset += 8;
        }
        if (!matched) while (offset < limit && buf[offset] != '\n') ++offset;
        int len = offset - begin;
        if (offset == limit) {
            httpBuf.write(buf, begin, len);
            return offset;
        }
        if (httpBuf.isEmpty()) {
            httpBuf.replace(buf, begin, len);
            this.headerValue = httpBuf.toISO_8859_1_string(true);
            httpBuf.reset();
            return offset;
        }
        httpBuf.write(buf, begin, len);
        this.headerValue = httpBuf.toISO_8859_1_string(true);
        httpBuf.clear();
        return offset;
    }

    private int readBoundary(byte[] buf, int offset, int limit) {
        if (offset == limit) return limit;
        byte b = buf[offset++];
        if (expectLF) {
            if (b != '\n') {
                status = HttpStatus.BAD_REQUEST;
                return limit;
            }
        } else if (b == '\r') {
            if (offset == limit) {
                expectLF = true;
                return limit;
            }
            if (buf[offset] == '\n')  ++offset; // byte after '\r'
            else {
                status = HttpStatus.BAD_REQUEST;
                return limit;
            }
        } // lone '\\n' without '\\r' also accepted as boundary

        prepareRequestContent();
        readState = ReadState.ReadBody;
        return readBody(buf, offset, limit);
    }

    protected int readBody(byte[] buf, int offset, int limit) {
        final int bodySize = httpBuf.size(), len = limit - offset;
        if (chunked) {
            completedBody(buf, offset, len, bodySize, BODY_MODE_CHUNKED);
            return limit;
        }
        if (contentLength <= 0 /*|| bodySize == contentLength*/) { // bodySize always < contentLength
            readState = ReadState.Completed;
            if (HttpConf.PIPELINE_ENABLED) {
                return offset; // zero-copy: remaining pipeline data starts here
            } else if (len > 0 && !hasContentLength) {
                status = HttpStatus.LENGTH_REQUIRED;
            } // if CL:0 with residual data, ignore and fall through to return limit
        } else {
            if (len == 0) return limit;
            if (contentLength < HttpConf.MAX_BODY_IN_MEMORY) {
                if (bodySize + len >= contentLength) {
                    int bodyLen = (int) contentLength - bodySize;
                    completedBody(buf, offset, bodyLen, bodySize, BODY_MODE_NORMAL);
                    return HttpConf.PIPELINE_ENABLED ? offset + bodyLen : limit; // zero-copy: remaining pipeline data starts here
                } else {
                    httpBuf.write(buf, offset, len);
                }
            } else {
                completedBody(buf, offset, len, bodySize, BODY_MODE_STREAM);
            }
        }
        return limit;
    }

    void completedBody(byte[] buf, int offset, int len, int hbSize, byte mode) {
        readState = ReadState.Completed;
        bodyMode = mode;
        if (hbSize == 0) body = Arrays.copyOfRange(buf, offset, offset + len);
        else {
            body = Arrays.copyOfRange(httpBuf.buf, httpBuf.begin, httpBuf.begin + hbSize + len);
            System.arraycopy(buf, offset, body, hbSize, len);
            httpBuf.reset();
        }
    }

    protected void onException(ChannelContext ctx, Throwable throwable) throws IOException {
        log.error("Protocol Error: {}", throwable.getMessage());
        ctx.invokeHandle(HttpBadRequest.PROTOCOL_ERROR_REQUEST);
        ctx.flush();
        reset();
    }

    protected void onDecoded(ChannelContext ctx) throws IOException {
        HttpVersion version = HttpVersion.of(startLineValues[2]);
        if(version == null) {
            protocolClose("unsupported version: " + startLineValues[2]); // protocol error
            return;
        }
        HttpMethod method = HttpMethod.fromString(startLineValues[0]);
        if (method == null) {
            onBadDecoded(ctx, HttpStatus.NOT_IMPLEMENTED); // Method value exists but not in whitelist → return 501
            return;
        }
        // HTTP/1.1 requires Host header (RFC 7230 §5.4)
        if (!hasHost && version == HttpVersion.HTTP_1_1) {
            onBadDecoded(ctx, HttpStatus.BAD_REQUEST);
            return;
        }
        if (bodyMode == BODY_MODE_NORMAL) {
            ctx.invokeHandle(new HttpDefaultRequest(method, startLineMiddle, uriDecoder.getUri(), uriDecoder.getParameters(), version, headers, body, contentLength, contentType, ctx));
        } else if (bodyMode == BODY_MODE_STREAM) {
            ctx.invokeHandle(new HttpStreamRequest(method, startLineMiddle, uriDecoder.getUri(), uriDecoder.getParameters(), version, headers, body, contentLength, contentType, ctx));
        } else {
            if(hasContentLength) {
                headers.remove(HttpHeaderNormalized.getContentLength()); // RFC 7230 §3.3.3: a sender MUST NOT transmit both Content-Length and Transfer-Encoding
            }
            ctx.invokeHandle(new HttpChunkedRequest(method, startLineMiddle, uriDecoder.getUri(), uriDecoder.getParameters(), version, headers, body, -1, contentType, ctx));
        }
    }

    protected void onBadDecoded(ChannelContext ctx, HttpStatus status) throws IOException {
        // Defensive: malformed start-line (tokens < 3) → close instead of building a request
        if (startLineIdx != 3) {
            ctx.close();
            return;
        }
        ctx.invokeHandle(new HttpBadRequest(HttpMethod.fromString(startLineValues[0]), startLineMiddle, uriDecoder.getUri(), uriDecoder.getParameters(), HttpVersion.of(startLineValues[2]), headers, body, contentLength, contentType, ctx).stream(bodyMode != BODY_MODE_NORMAL).chunked(bodyMode == BODY_MODE_CHUNKED).status(status));
        if (status == HttpStatus.REQUEST_TIMEOUT)
            ctx.close(); // Defensive close: no Connection: close header sent to potentially malicious client
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    void addHeader(String name, String value) { // note: the name is normalized and name-value is all iso-8859-1
        if(!shallow) {
            int singleHeaderSize = name.length() + value.length() + 4;
            if (singleHeaderSize > HttpConf.MAX_SINGLE_HEADER_SIZE
                    || (totalHeaderSize += singleHeaderSize) > HttpConf.MAX_HTTP_HEADER_SIZE) {
                status = HttpStatus.REQUEST_HEADER_FIELDS_TOO_LARGE;
                return;
            }
        }
        handleSpecialHeader(name, value);
        Object prev = headers.get(name);
        if (prev == null) {
            headers.put(name, value);
        } else if (prev instanceof String) {
            List<String> list = new ArrayList<String>(2);
            list.add((String) prev);
            list.add(value);
            headers.put(name, list);
        } else {
            ((List) prev).add(value);
        }
    }

    /** Dispatch known headers (content-type/content-length/host/expect/transfer-encoding) by last-2-char short key. */
    private void handleSpecialHeader(String name, String value) {
        if (name.length() < 2) return;
        short key = (short) (name.charAt(name.length() - 2) << 8 | name.charAt(name.length() - 1));
        switch (key) {
            case K_CONTENT_TYPE:
                if (HttpHeaderNormalized.getContentType().equals(name)) this.contentType = value;
                break;
            case K_CONTENT_LENGTH:
                if (HttpHeaderNormalized.getContentLength().equals(name)) {
                    if (hasContentLength) {
                        this.status = HttpStatus.BAD_REQUEST;
                        break;
                    }
                    try {
                        this.contentLength = Long.parseLong(value);
                    } catch (NumberFormatException t) {
                        this.status = HttpStatus.BAD_REQUEST;
                        return;
                    }
                    this.hasContentLength = true;
                    if (this.contentLength < 0) {
                        this.status = HttpStatus.BAD_REQUEST;
                    } else if (this.contentLength > HttpConf.BODY_MAX_SIZE) {
                        this.status = HttpStatus.REQUEST_ENTITY_TOO_LARGE;
                    }
                }
                break;
            case K_HOST:
                if (HttpHeaderNormalized.getHost().equals(name)) {
                    if (hasHost || value.isEmpty()) this.status = HttpStatus.BAD_REQUEST;
                    else this.hasHost = true;
                }
                break;
            case K_EXPECT:
                if (HttpHeaderNormalized.getExpect().equals(name)) {
                    if (!HttpHeaderValues.CONTINUE.equalsIgnoreCase(value)) this.status = HttpStatus.EXPECTATION_FAILED;
                    else this.expectContinue = true;
                }
                break;
            case K_TRANSFER_ENCODING:
                if (HttpHeaderNormalized.getTransferEncoding().equals(name) && value.toLowerCase().contains(HttpHeaderValues.CHUNKED)) {
                    this.chunked = true; // RFC 7230 §3.3.3: Transfer-Encoding overrides Content-Length when both present
                }
                break;
        }
    }

    public void reset() {
        readState = ReadState.Init;
        startLineIdx = totalHeaderSize = 0;
        requestStartTime = contentLength = 0;
        expectLF = expectContinue = chunked = hasContentLength = hasHost = false;
        status = null;
        httpBuf.reset();
        startLineMiddle = null;
        headerState = HEADER_KEY;
        headers.clear();
        contentType = null;
        bodyMode = BODY_MODE_NORMAL;
        body = HttpRequest.EMPTY_BODY;
        uriDecoder.reset();
    }

    public enum ReadState {
        Init, ReadHeader, ReadBoundary, ReadBody, Completed
    }
}

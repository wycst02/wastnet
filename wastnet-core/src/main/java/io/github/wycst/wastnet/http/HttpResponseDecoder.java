package io.github.wycst.wastnet.http;

import io.github.wycst.wastnet.log.Log;
import io.github.wycst.wastnet.log.LogFactory;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.io.IOException;

/**
 * HTTP/1.x response message decoder.
 * <p>
 * Decodes an HTTP response from raw bytes. The response format is:
 * <pre>
 * HTTP/1.1 200 OK\r\n
 * Content-Type: text/html\r\n
 * \r\n
 * body...
 * </pre>
 * </p>
 * <p>
 * This class reuses all parsing logic from {@link HttpRequestDecoder}.
 * The parent's {@link #readStartLine(byte[], int, int)} parses
 * the status line tokens (version, status code, reason phrase) into the same
 * internal fields.
 * </p>
 * <p>
 * Status line token mapping for response:
 * <pre>
 * rpc[0]         = HTTP version (e.g. "HTTP/1.1")
 * secondTokenBytes  = status code ASCII bytes (e.g. "200")
 * rpc[2]         = reason phrase (e.g. "OK")
 * </pre>
 * </p>
 * <p>
 * Usage:
 * <pre>
 * HttpResponseDecoder decoder = new HttpResponseDecoder();
 * decoder.decode(bytes, 0, bytes.length, ctx);
 * </pre>
 * </p>
 *
 * @author wangyc
 */
public class HttpResponseDecoder extends HttpRequestDecoder {

    static final Log log = LogFactory.getLog(HttpResponseDecoder.class);

    public HttpResponseDecoder() {
    }

    /**
     * Disable 100-continue handling for response decoding.
     * Expect header is a request-only concept and has no meaning in responses.
     */
    @Override
    protected void prepareRequestContent() {
    }

    /**
     * Parse the status code from ASCII bytes.
     *
     * @return the parsed status code, or -1 if parsing fails
     */
    protected int parseStatusCode() {
        if (startLineMiddle == null) return -1;
        int code = 0;
        for (byte b : startLineMiddle) {
            if (b < '0' || b > '9') return -1;
            code = code * 10 + (b - '0');
        }
        return code;
    }

    @Override
    protected void onDecodeUri(byte[] secondTokenBytes) {
    }

    @Override
    protected void onDecoded(ChannelContext ctx) throws IOException {
        HttpVersion version = HttpVersion.of(startLineValues[0]);
        int code = parseStatusCode();
        String reason = startLineValues[2];
        HttpDecodedResponse response;
        if (bodyMode == BODY_MODE_CHUNKED) {
            HttpBodyInputStream chunkedStream = new HttpChunkedStream(body, ctx);
            response = new HttpDecodedResponse(version, code, reason,
                    headers, body, contentLength, contentType, true, chunkedStream);
        } else if (bodyMode == BODY_MODE_STREAM) {
            HttpBodyInputStream bodyInputStream = new HttpBodyInputStream(contentLength, body, ctx);
            response = new HttpDecodedResponse(version, code, reason,
                    headers, body, contentLength, contentType, true, bodyInputStream);
        } else {
            response = new HttpDecodedResponse(version, code, reason,
                    headers, body, contentLength, contentType);
        }
        ctx.invokeHandle(response);
    }

    @Override
    protected void onBadDecoded(ChannelContext ctx, HttpStatus status) throws IOException {
        HttpVersion version = startLineValues[0] != null ? HttpVersion.of(startLineValues[0]) : HttpVersion.HTTP_1_1;
        int code = status != null ? status.code : 400;
        String reason = status != null ? status.text : "Bad Request";
        ctx.invokeHandle(new HttpDecodedResponse(version, code, reason,
                headers, body, contentLength, contentType));
        if (status == HttpStatus.REQUEST_TIMEOUT) {
            ctx.close();
        }
    }

    @Override
    protected void onException(ChannelContext ctx, Throwable throwable) throws IOException {
        log.error("Protocol Error: {}", throwable.getMessage());
        ctx.invokeHandle(new HttpDecodedResponse(HttpVersion.HTTP_1_1, 400, "Bad Request",
                headers, body, contentLength, contentType));
    }

    /**
     * Construct and return the decoded response from current parser state.
     *
     * @return the decoded response, or null if status line not parsed
     */
    @Override
    public HttpMessage getResult() {
        if (startLineValues[0] == null) return null;
        HttpVersion version = HttpVersion.of(startLineValues[0]);
        int code = parseStatusCode();
        String reason = startLineValues[2];
        return new HttpDecodedResponse(version, code, reason,
                headers, body, contentLength, contentType);
    }
}

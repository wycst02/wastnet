package io.github.wycst.wastnet.http;

import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * HTTP request with a streamable body.
 * <p>
 * Unlike {@link HttpDecodedRequest} which buffers the entire body in memory,
 * this request delivers the payload via an {@link HttpBodyInputStream}. Two
 * subtypes exist:
 * <ul>
 *   <li>{@link HttpChunkedRequest} — chunked transfer encoding</li>
 *   <li>Non-chunked streaming requests with a known {@code content-length}</li>
 * </ul>
 * Callers should prefer {@link #bodyStream()} for large payloads to avoid OOM.
 *
 * @since 2024-2-8
 * @author wangyc
 */
public class HttpStreamRequest extends HttpDecodedRequest {

    private final HttpBodyInputStream bodyStream;

    public HttpStreamRequest(
            HttpMethod method,
            byte[] uriAsciiBytes,
            String requestUri,
            Map<String, List<String>> parameters,
            HttpVersion httpVersion,
            Map<String, Object> headers,
            byte[] body,
            long contentLength,
            String contentType,
            ChannelContext ctx) {
        this(method, uriAsciiBytes, requestUri, parameters, httpVersion, headers, body, contentLength, contentType, ctx, new HttpBodyInputStream(contentLength, body, ctx));
    }

    protected HttpStreamRequest(
            HttpMethod method,
            byte[] uriAsciiBytes,
            String requestUri,
            Map<String, List<String>> parameters,
            HttpVersion httpVersion,
            Map<String, Object> headers,
            byte[] body,
            long contentLength,
            String contentType,
            ChannelContext ctx,
            HttpBodyInputStream bodyStream
    ) {
        super(method, uriAsciiBytes, requestUri, parameters, httpVersion, headers, body, contentLength, contentType, ctx);
        this.bodyStream = bodyStream;
    }

    @Override
    public final boolean isStream() {
        return true;
    }

    @Override
    public InputStream bodyStream() {
        return bodyStream;
    }

    @Override
    public boolean isCompleted() {
        return bodyStream.checkCompleted();
    }

    @Override
    public void complete() {
        super.complete();
        bodyStream.complete();
    }

    /**
     * Get body data as byte array.
     * <p>
     * Rejects if body size exceeds 2x MAX_BODY_IN_MEMORY to prevent OOM.
     * For large data, use {@link #bodyStream()} instead.
     *
     * @return body data as byte array
     * @throws IllegalStateException if body size exceeds maximum limit
     */
    @Override
    public byte[] getBodyData() {
        if (contentLength > (long) HttpConf.MAX_BODY_IN_MEMORY << 1) {
            throw new IllegalStateException(
                    "Body size (" + contentLength + " bytes) exceeds maximum limit (" +
                            (HttpConf.MAX_BODY_IN_MEMORY << 1) + " bytes). Data too large, not supported."
            );
        }
        return bodyStream.readFullBytes();
    }

    /**
     * Delegate the request body to a target channel context using streaming.
     * This method reads from the body stream and writes to the target channel,
     * which helps prevent OOM for large requests.
     *
     * @param targetCtx the target channel context to delegate to
     * @throws Throwable if delegation fails
     */
    protected void delegateBody(ChannelContext targetCtx) throws Throwable {
        byte[] buffer = new byte[8192];
        InputStream stream = bodyStream();
        int bytesRead;
        while ((bytesRead = stream.read(buffer)) != -1) {
            targetCtx.write(buffer, 0, bytesRead);
        }
    }
}

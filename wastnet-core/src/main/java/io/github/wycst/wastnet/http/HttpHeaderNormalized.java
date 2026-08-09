package io.github.wycst.wastnet.http;

/**
 * Standardized HTTP header name constants and accessors.
 * <p>
 * All methods return the header name formatted according to the global
 * {@link HttpHeaderUtils.HeaderConfig} (lowercase or Title Case).
 * <p>
 * Method names omit the "Normalized" suffix since the class name already
 * conveys the purpose.
 *
 * @author wangyc
 */
public class HttpHeaderNormalized {

    private static String CONTENT_PREFIX = "content-";
    private static String CONTENT_TYPE = "content-type";
    private static String CONTENT_LENGTH = "content-length";

    static {
        reinitializeContentHeaders();
    }

    static void reinitializeContentHeaders() {
        if (HttpHeaderUtils.isTitleCase()) {
            CONTENT_PREFIX = "Content-";
            CONTENT_TYPE = "Content-Type";
            CONTENT_LENGTH = "Content-Length";
        } else {
            CONTENT_PREFIX = "content-";
            CONTENT_TYPE = "content-type";
            CONTENT_LENGTH = "content-length";
        }
    }

    public static String getContentPrefix() {
        return CONTENT_PREFIX;
    }

    public static String getContentType() {
        return CONTENT_TYPE;
    }

    public static String getContentLength() {
        return CONTENT_LENGTH;
    }

    public static String getContentEncoding() {
        return HttpHeaderUtils.isTitleCase() ? "Content-Encoding" : "content-encoding";
    }

    public static String getContentDisposition() {
        return HttpHeaderUtils.isTitleCase() ? "Content-Disposition" : "content-disposition";
    }

    public static String getAcceptEncoding() {
        return HttpHeaderUtils.isTitleCase() ? "Accept-Encoding" : "accept-encoding";
    }

    public static String getTransferEncoding() {
        return HttpHeaderUtils.isTitleCase() ? "Transfer-Encoding" : "transfer-encoding";
    }

    public static String getAllow() {
        return HttpHeaderUtils.isTitleCase() ? "Allow" : "allow";
    }

    public static String getUpgrade() {
        return HttpHeaderUtils.isTitleCase() ? "Upgrade" : "upgrade";
    }

    public static String getConnection() {
        return HttpHeaderUtils.isTitleCase() ? "Connection" : "connection";
    }

    public static String getHost() {
        return HttpHeaderUtils.isTitleCase() ? "Host" : "host";
    }

    public static String getOrigin() {
        return HttpHeaderUtils.isTitleCase() ? "Origin" : "origin";
    }

    public static String getDate() {
        return HttpHeaderUtils.isTitleCase() ? "Date" : "date";
    }

    public static String getServer() {
        return HttpHeaderUtils.isTitleCase() ? "Server" : "server";
    }

    public static String getExpect() {
        return HttpHeaderUtils.isTitleCase() ? "Expect" : "expect";
    }

    public static String getLastModified() {
        return HttpHeaderUtils.isTitleCase() ? "Last-Modified" : "last-modified";
    }

    public static String getETag() {
        return HttpHeaderUtils.isTitleCase() ? "ETag" : "etag";
    }

    public static String getIfNoneMatch() {
        return HttpHeaderUtils.isTitleCase() ? "If-None-Match" : "if-none-match";
    }

    public static String getIfModifiedSince() {
        return HttpHeaderUtils.isTitleCase() ? "If-Modified-Since" : "if-modified-since";
    }

    public static String getSecWebSocketKey() {
        return HttpHeaderUtils.isTitleCase() ? "Sec-WebSocket-Key" : "sec-websocket-key";
    }

    public static String getSecWebSocketProtocol() {
        return HttpHeaderUtils.isTitleCase() ? "Sec-WebSocket-Protocol" : "sec-websocket-protocol";
    }

    public static String getCacheControl() {
        return HttpHeaderUtils.isTitleCase() ? "Cache-Control" : "cache-control";
    }
}

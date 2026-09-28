package io.github.wycst.wastnet.http.annotation;

import io.github.wycst.wastnet.http.HttpHeaderValues;

/**
 * Common response content types used to declare what an endpoint produces.
 * <p>
 * Each value carries the full Content-Type header value with a fixed UTF-8
 * charset. {@link #CUSTOM} carries no fixed header value; the converter decides
 * the content-type itself in that case.
 *
 * @author wangyc
 */
public enum ContentType {
    JSON(HttpHeaderValues.APPLICATION_JSON_UTF8),
    TEXT(HttpHeaderValues.TEXT_PLAIN_UTF8),
    HTML(HttpHeaderValues.TEXT_HTML_UTF8),
    XML(HttpHeaderValues.APPLICATION_XML_UTF8),
    CUSTOM(null);

    private final String contentType;

    ContentType(String contentType) {
        this.contentType = contentType;
    }

    /**
     * @return the full Content-Type header value with UTF-8 charset
     *         (e.g. {@code "application/json;charset=utf-8"}), or {@code null}
     *         for {@link #CUSTOM} where the converter decides the content-type itself
     */
    public String getContentType() {
        return contentType;
    }

    /**
     * Map a request Content-Type header to the corresponding {@link ContentType}.
     *
     * @param contentType the raw Content-Type header value (may be {@code null})
     * @return the matched enum, or {@code null} if it cannot be mapped
     */
    public static ContentType fromRequest(String contentType) {
        if (contentType == null) {
            return null;
        }
        String ct = contentType.toLowerCase();
        if (ct.contains("json")) return JSON;
        if (ct.contains("xml")) return XML;
        if (ct.contains("html")) return HTML;
        if (ct.contains("plain")) return TEXT;
        return null;
    }
}

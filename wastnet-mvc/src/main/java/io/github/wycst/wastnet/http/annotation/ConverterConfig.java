package io.github.wycst.wastnet.http.annotation;

/**
 * Serialization/deserialization configuration for an endpoint method.
 * <p>
 * Immutable: constructed once during scanning (typically from a {@link MethodRouteInfo})
 * and never mutated afterwards.
 *
 * @author wangyc
 */
public class ConverterConfig {

    private boolean pretty;
    private boolean skipNull;
    private String dateFormat;
    // ── Response content-type (resolved once at scan/init time) ──
    private final ContentType responseType;

    /** Default constructor — every field at its immutable default (JSON response). */
    public ConverterConfig() {
        this.responseType = ContentType.JSON;
    }

    /**
     * Construct from a route, applying the endpoint's declared response type
     * (falls back to {@link ContentType#JSON} when unset).
     *
     * @param routeInfo the endpoint route metadata
     */
    public ConverterConfig(MethodRouteInfo routeInfo) {
        ContentType rt = routeInfo.getResponseType();
        this.responseType = rt == null ? ContentType.JSON : rt;
    }

    // ── Read-only properties ──

    /** @return whether to pretty-print serialized output */
    public boolean isPretty() { return pretty; }

    /** @return whether null-valued fields are excluded from output */
    public boolean isSkipNull() { return skipNull; }

    /** @return the date/time format pattern, or {@code null} */
    public String getDateFormat() { return dateFormat; }

    /** @return the content type this endpoint produces (never {@code null}) */
    public ContentType getResponseType() { return responseType; }

    /** @return the full response Content-Type header value with UTF-8 charset */
    public String getResponseContentType() {
        return responseType.getContentType();
    }

    /** @return true if the endpoint produces JSON (default) */
    public boolean isJson() { return responseType == ContentType.JSON; }

    /** @return true if the endpoint produces a textual type (XML / TEXT / HTML) */
    public boolean isTextual() {
        return responseType == ContentType.XML
                || responseType == ContentType.TEXT
                || responseType == ContentType.HTML;
    }

    /** @return true if the endpoint declares the custom {@link ContentType#CUSTOM} */
    public boolean isCustom() { return responseType == ContentType.CUSTOM; }
}

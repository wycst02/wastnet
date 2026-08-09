package io.github.wycst.wastnet.http.annotation;

import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;

import java.util.HashMap;
import java.util.Map;

/**
 * Serialization/deserialization configuration for endpoint methods.
 * <p>
 * Each endpoint gets its own instance, constructed during scanning.
 * The framework populates it from global defaults and per-endpoint settings.
 *
 * @author wangyc
 */
public class ConverterConfig {

    private boolean pretty;
    private boolean skipNull;
    private String dateFormat;
    private final Map<String, String> properties = new HashMap<String, String>();

    /** Default constructor — leaves all fields at defaults. */
    public ConverterConfig() {
    }

    // ── Standard properties ──

    /** @return whether to pretty-print serialized output */
    public boolean isPretty() { return pretty; }

    /**
     * @param pretty whether to pretty-print serialized output
     * @return this for chaining
     */
    public ConverterConfig pretty(boolean pretty) { this.pretty = pretty; return this; }

    /** @return whether null-valued fields are excluded from output */
    public boolean isSkipNull() { return skipNull; }

    /**
     * @param skipNull whether null-valued fields are excluded from output
     * @return this for chaining
     */
    public ConverterConfig skipNull(boolean skipNull) { this.skipNull = skipNull; return this; }

    /** @return the date/time format pattern, or empty string */
    public String getDateFormat() { return dateFormat; }

    /**
     * @param dateFormat date/time format pattern (e.g. "yyyy-MM-dd HH:mm:ss")
     * @return this for chaining
     */
    public ConverterConfig dateFormat(String dateFormat) { this.dateFormat = dateFormat; return this; }

    // ── Response content-type (resolved once at scan/init time) ──

    private ContentType responseType = ContentType.JSON;

    /**
     * @return the content type this endpoint produces (never {@code null})
     */
    public ContentType getResponseType() { return responseType; }

    /**
     * Set the content type this endpoint produces.
     *
     * @param responseType the content type, or {@code null} to fall back to {@link ContentType#JSON}
     * @return this for chaining
     */
    public ConverterConfig responseType(ContentType responseType) {
        this.responseType = responseType == null ? ContentType.JSON : responseType;
        return this;
    }

    /**
     * @return the full Content-Type header value with UTF-8 charset
     *         (e.g. {@code "application/json;charset=utf-8"})
     */
    public String getContentType() {
        return responseType.getContentType();
    }

    /** @return true if the endpoint produces JSON (default) */
    public boolean isJson() { return responseType == ContentType.JSON; }

    /** @return true if the endpoint produces XML */
    public boolean isXml() { return responseType == ContentType.XML; }

    /** @return true if the endpoint produces plain text */
    public boolean isText() { return responseType == ContentType.TEXT; }

    /** @return true if the endpoint produces HTML */
    public boolean isHtml() { return responseType == ContentType.HTML; }

    /** @return true if the endpoint produces a textual type (XML / TEXT / HTML) */
    public boolean isTextual() {
        return responseType == ContentType.XML
                || responseType == ContentType.TEXT
                || responseType == ContentType.HTML;
    }

    /** @return true if the endpoint declares the custom {@link ContentType#CUSTOM},
     *          i.e. the converter decides the content-type itself */
    public boolean isCustom() { return responseType == ContentType.CUSTOM; }

    // ── Custom properties (converter-specific) ──

    /** @return the underlying custom properties map (mutable) */
    public Map<String, String> getProperties() { return properties; }

    /**
     * @param key   custom property key
     * @param value custom property value
     * @return this for chaining
     */
    public ConverterConfig property(String key, String value) { properties.put(key, value); return this; }

    /**
     * @param props bulk properties to merge in
     * @return this for chaining
     */
    public ConverterConfig properties(Map<String, String> props) { properties.putAll(props); return this; }

    /**
     * @param key property key
     * @return the value, or {@code null} if not set
     */
    public String getProperty(String key) { return properties.get(key); }

    /**
     * @param key          property key
     * @param defaultValue returned when key is absent
     * @return the value, or {@code defaultValue} if not set
     */
    public String getProperty(String key, String defaultValue) {
        String v = properties.get(key);
        return v != null ? v : defaultValue;
    }

    /**
     * Hook invoked by the framework right before the message converter serializes
     * a {@code @ResponseBody} result. The default implementation does nothing;
     * subclasses may override to apply custom pre-processing (e.g. content
     * negotiation based on the request's Accept header).
     *
     * @param request  the HTTP request
     * @param response the HTTP response
     * @param result   the return value of the endpoint method (may be null)
     */
    public void beforeResponseBody(HttpRequest request, HttpResponse response, Object result) throws Throwable {
    }
}

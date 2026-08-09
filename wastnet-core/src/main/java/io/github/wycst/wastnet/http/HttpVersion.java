package io.github.wycst.wastnet.http;

/**
 * @since 2024-1-21
 * @author wangyc
 */
public enum HttpVersion {

    HTTP_1_0("HTTP/1.0"),
    HTTP_1_1("HTTP/1.1"),
    HTTP_2("HTTP/2"), // RFC 9113
    HTTP_3("HTTP/3"); // unsupported (IETF RFC 9114)

    private final String version;

    HttpVersion(String version) {
        this.version = version;
    }

    /**
     * Parse an {@code HttpVersion} from its wire representation (e.g. {@code "HTTP/1.1"}).
     * <p>
     * Only HTTP/1.x versions are decoded from the wire. HTTP/2 and HTTP/3 bypass this
     * method entirely — they are assigned directly by
     * {@link io.github.wycst.wastnet.http.h2.Http2Request#getHttpVersion()}.
     *
     * @param version the version string from the request start line
     * @return the matching {@code HttpVersion}, or {@code null} if unsupported
     */
    public static HttpVersion of(String version) {
        if (HTTP_1_1.version.equalsIgnoreCase(version)) {
            return HTTP_1_1;
        }
        if (HTTP_1_0.version.equalsIgnoreCase(version)) {
            return HTTP_1_0;
        }
        return null; // not supported (HTTP/2+ bypass this method entirely)
    }

    @Override
    public String toString() {
        return version;
    }
}

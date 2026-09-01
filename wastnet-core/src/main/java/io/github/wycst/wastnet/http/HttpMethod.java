package io.github.wycst.wastnet.http;

import io.github.wycst.wastnet.log.Log;
import io.github.wycst.wastnet.log.LogFactory;

import java.util.LinkedHashSet;
/**
 * Standard HTTP methods (RFC 7231, RFC 5789, RFC 7230) and common extension methods.
 * <p>
 * Enum constants are ordered by frequency of use.
 * {@link #ordinal()} is used as array index for O(1) handler dispatch.
 * {@link #name()} returns the standard uppercase string (e.g. {@code "GET"}).
 * <p>
 * Methods with hyphens in their canonical form (e.g. {@code VERSION-CONTROL})
 * cannot use {@code name()} directly and are currently excluded.
 *
 * @author wangyc
 */
public enum HttpMethod {

    // Standard methods (RFC 7231, RFC 5789)
    GET,
    POST,
    QUERY,      // RFC 9652 (2025): semantically idempotent, read-only query
    PUT,
    DELETE,
    OPTIONS,
    PATCH,
    HEAD,
    TRACE,
    CONNECT,

    // WebDAV (RFC 4918)
    PROPFIND,
    PROPPATCH,
    MKCOL,
    COPY,
    MOVE,
    LOCK,
    UNLOCK,

    // Additional common extensions
    SEARCH,      // RFC 5323
    REPORT,      // RFC 3253
    MERGE,       // RFC 3253
    UPDATE,      // RFC 3253
    CHECKOUT,    // RFC 3253
    ACL,         // RFC 3744
    MKCALENDAR,  // RFC 4791
    PURGE,       // Cache invalidation (e.g. Varnish, Nginx)
    NOTIFY,      // RFC 4640 (push notification)
    SUBSCRIBE,   // RFC 4640
    UNSUBSCRIBE, // RFC 4640
    BIND,        // RFC 5842
    UNBIND,      // RFC 5842
    REBIND,      // RFC 5842
    LINK,        // RFC 5988
    UNLINK;      // RFC 5988

    // ── Logger ──

    private static final Log log = LogFactory.getLog(HttpMethod.class);

    private static final HttpMethod[] SUPPORTED_METHODS;

    static {
        HttpMethod[] implemented = values();
        String implementedMethods = HttpConf.IMPLEMENTED_METHODS;
        if (implementedMethods != null && !implementedMethods.isEmpty()) {
            try {
                String[] parts = implementedMethods.split(",");
                LinkedHashSet<HttpMethod> set = new LinkedHashSet<HttpMethod>();
                for (String part : parts) {
                    set.add(valueOf(part.trim().toUpperCase()));
                }
                implemented = set.toArray(new HttpMethod[0]);
            } catch (Exception e) {
                log.warn("Invalid implemented-methods config '{}', fallback to all methods: {}", implementedMethods, e.getMessage());
            }
        }
        SUPPORTED_METHODS = implemented;
    }

    /**
     * Look up an HttpMethod in the whitelist.
     * <p>
     * Returns {@code null} if the method is not recognised,
     * allowing graceful fallback in the decoder.
     *
     * @param method the HTTP method string (case-sensitive, typically uppercase)
     * @return the matching enum constant, or {@code null} if not whitelisted
     */
    public static HttpMethod fromString(String method) {
        if (method == null) return null;
        for (HttpMethod m : SUPPORTED_METHODS) {
            if (m.name().equals(method)) return m;
        }
        return null;
    }
}

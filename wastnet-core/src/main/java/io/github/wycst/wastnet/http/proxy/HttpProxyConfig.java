package io.github.wycst.wastnet.http.proxy;

import io.github.wycst.wastnet.http.HttpHeaderUtils;
import io.github.wycst.wastnet.http.HttpRequest;

import javax.net.ssl.TrustManager;
import java.util.*;
import java.util.regex.Pattern;

// Proxy configuration
public class HttpProxyConfig {

    final String target;
    // Allow protocol upgrade (WebSocket, h2c, etc.)
    boolean upgrade;
    boolean changeOrigin = true;
    RewriteRule rewriteRule;
    int readTimeout = 60000;
    int connectionTimeout = 5000;
    // Loop detection
    boolean loopDetection;
    // Custom TrustManagers for SSL (null = use TRUST_ALL)
    TrustManager[] trustManagers;
    // Verify hostname for HTTPS proxy connections (default true)
    boolean verifyHostname = true;
    /**
     * Whether the target server supports HTTP/2.
     * <p>
     * Only takes effect when the proxy server itself is serving HTTP/2.
     * If the client connects via HTTP/1.1, the proxy will always use HTTP/1.1 to the target
     * regardless of this setting.
     */
    boolean http2;
    // Allowed TLS protocols for outbound proxy connections (null = JVM default)
    String[] enabledProtocols;

    /**
     * Enable h2c prior knowledge for non-SSL target connections.
     * <p>
     * When true, the proxy sends the HTTP/2 connection preface directly
     * over a cleartext TCP connection, without ALPN or Upgrade negotiation.
     * <p>
     * <strong>Differences from {@link #http2}:</strong>
     * <ul>
     *   <li>{@code http2(true)} negotiates H2 via ALPN over TLS — the target can
     *       still respond in HTTP/1.1 if it doesn't support H2 (<strong>fallback allowed</strong>).</li>
     *   <li>{@code h2c(true)} forces H2 via prior knowledge — if the target does not
     *       support H2, the connection will fail (<strong>no fallback</strong>).</li>
     *   <li>{@code h2c} only takes effect when the target URL is {@code http://} (non-SSL).</li>
     * </ul>
     */
    boolean h2c;

    // Headers: name -> resolver
    final Map<String, HeaderValueResolver> headers = new LinkedHashMap<>();
    // Headers to remove
    final Set<String> removedHeaders = new HashSet<>();

    private HttpProxyConfig(String target) {
        this.target = target;
    }

    /**
     * Create a new proxy configuration.
     *
     * @param target target URL
     * @return new proxy configuration
     */
    public static HttpProxyConfig target(String target) {
        return new HttpProxyConfig(target);
    }

    /**
     * Rewrite path.
     * <p>
     * If true, the path will be rewritten to the target URL.
     * If false, the path will be passed as-is to the target URL.
     *
     * @param rewrite true to rewrite path, false to pass as-is
     * @return this config for chaining
     */
    public HttpProxyConfig rewrite(boolean rewrite) {
        this.rewriteRule = rewrite ? RewriteRule.IDENTITY : null;
        return this;
    }

    /**
     * Rewrite path using a custom function.
     *
     * @param rewrite rewrite function
     * @return this config for chaining
     */
    public HttpProxyConfig rewrite(RewriteFunction rewrite) {
        if (rewrite != null) {
            this.rewriteRule = RewriteRule.function(rewrite);
        }
        return this;
    }

    /**
     * Prefix path replacement: strip the prefix and prepend replacement.
     * <p>
     * Example: replacePrefix("/api", "") — /api/users → /users
     * Example: replacePrefix("/api", "/v2") — /api/users → /v2/users
     *
     * @param prefix      prefix to strip from path
     * @param replacement replacement to prepend
     * @return this config for chaining
     */
    public HttpProxyConfig replacePrefix(String prefix, String replacement) {
        this.rewriteRule = RewriteRule.prefix(prefix, replacement);
        return this;
    }

    /**
     * Regex path replacement.
     * <p>
     * Example: replaceRegex("^/api/(.*)$", "/$1") — /api/users → /users
     *
     * @param pattern     regex pattern
     * @param replacement replacement string (supports $1, $2, etc.)
     * @return this config for chaining
     */
    public HttpProxyConfig replaceRegex(String pattern, String replacement) {
        this.rewriteRule = RewriteRule.regex(pattern, replacement);
        return this;
    }

    public void setUpgrade(boolean upgrade) {
        this.upgrade = upgrade;
    }

    public HttpProxyConfig upgrade(boolean upgrade) {
        this.upgrade = upgrade;
        return this;
    }

    public void setChangeOrigin(boolean changeOrigin) {
        this.changeOrigin = changeOrigin;
    }

    public HttpProxyConfig changeOrigin(boolean changeOrigin) {
        this.changeOrigin = changeOrigin;
        return this;
    }

    public HttpProxyConfig readTimeout(int readTimeout) {
        this.readTimeout = readTimeout;
        return this;
    }

    public void setReadTimeout(int readTimeout) {
        this.readTimeout = readTimeout;
    }

    public HttpProxyConfig connectionTimeout(int connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
        return this;
    }

    public void setConnectionTimeout(int connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
    }

    public HttpProxyConfig loopDetection(boolean loopDetection) {
        this.loopDetection = loopDetection;
        return this;
    }

    public boolean isLoopDetection() {
        return loopDetection;
    }

    public HttpProxyConfig http2(boolean http2) {
        this.http2 = http2;
        return this;
    }

    public HttpProxyConfig enabledProtocols(String... enabledProtocols) {
        this.enabledProtocols = enabledProtocols;
        return this;
    }

    public HttpProxyConfig h2c(boolean h2c) {
        this.h2c = h2c;
        return this;
    }

    public HttpProxyConfig trustManagers(TrustManager[] trustManagers) {
        this.trustManagers = trustManagers;
        return this;
    }

    public HttpProxyConfig verifyHostname(boolean verifyHostname) {
        this.verifyHostname = verifyHostname;
        return this;
    }

    public boolean isVerifyHostname() {
        return verifyHostname;
    }

    /**
     * Add a header to the proxied request.
     * <p>
     * If the value contains Nginx-style variables (e.g. "$remote_addr"),
     * it will be resolved dynamically per request via {@link HttpProxyVariables}.
     * Otherwise, the value is treated as a static literal.
     * <p>
     * Examples:
     * <pre>
     * config.addHeader("X-Real-IP", "$remote_addr");        // dynamic
     * config.addHeader("X-Forwarded-Proto", "$scheme");      // dynamic
     * config.addHeader("X-Custom", "fixed-value");           // static
     * config.addHeader("X-URL", "$scheme://$host");          // composite dynamic
     * </pre>
     *
     * @param name  header name
     * @param value header value (may contain $variables)
     * @return this config for chaining
     */
    public HttpProxyConfig addHeader(String name, final String value) {
        String normalizedName = HttpHeaderUtils.normalizeHeaderKey(name);
        if (HttpProxyVariables.containsVariable(value)) {
            headers.put(normalizedName, request -> HttpProxyVariables.resolve(value, request));
        } else {
            headers.put(normalizedName, request -> value);
        }
        return this;
    }

    /**
     * Add a dynamic header resolved per request (e.g. X-Real-IP from client connection).
     *
     * @param name     header name
     * @param provider value provider resolved at request time
     * @return this config for chaining
     */
    public HttpProxyConfig addHeader(String name, HeaderValueResolver provider) {
        headers.put(HttpHeaderUtils.normalizeHeaderKey(name), provider);
        return this;
    }

    /**
     * Remove headers from the proxied request.
     *
     * @param names header names to remove
     * @return this config for chaining
     */
    public HttpProxyConfig removeHeader(String... names) {
        removedHeaders.addAll(Arrays.asList(names));
        return this;
    }

    public void setRewrite(RewriteFunction rewrite) {
        this.rewriteRule = rewrite != null ? RewriteRule.function(rewrite) : null;
    }

    public void setRewriteRule(RewriteRule rewriteRule) {
        this.rewriteRule = rewriteRule;
    }

    /**
     * Rewrite function interface (JDK 6 compatible)
     */
    public interface RewriteFunction {
        String rewrite(String path);
    }


    /**
     * Dynamic header value provider, resolved per request.
     * <p>
     * Example usage:
     * <pre>
     * config.addHeader("X-Real-IP", new HeaderValueResolver() {
     *     public String resolve(HttpRequest request) {
     *         return request.getRemoteAddress();
     *     }
     * });
     * </pre>
     */
    public interface HeaderValueResolver {
        String resolve(HttpRequest request);
    }


    /**
     * Unified rewrite rule supporting multiple strategies:
     * <ul>
     *   <li>{@link Type#PREFIX_REPLACE} — strip prefix and prepend replacement</li>
     *   <li>{@link Type#REGEX_REPLACE} — regex pattern match and replace</li>
     *   <li>{@link Type#FUNCTION} — custom rewrite function</li>
     * </ul>
     * <p>
     * This class is designed to be serializable for future configuration file support
     * (PREFIX_REPLACE/REGEX_REPLACE types are fully data-driven).
     */
    public static final class RewriteRule {

        /**
         * Rewrite strategy type
         */
        public enum Type {
            IDENTITY, PREFIX_REPLACE, REGEX_REPLACE, FUNCTION
        }

        /**
         * Identity rewrite rule that returns the original path unchanged
         */
        public static final RewriteRule IDENTITY = new RewriteRule(Type.IDENTITY);

        final Type type;
        final String from;
        final String to;
        final transient Pattern compiledPattern;
        final transient RewriteFunction function;

        private RewriteRule(Type type) {
            this(type, null, null, null, null);
        }

        private RewriteRule(Type type, String from, String to, Pattern compiledPattern, RewriteFunction function) {
            this.type = type;
            this.from = from;
            this.to = to;
            this.compiledPattern = compiledPattern;
            this.function = function;
        }

        /**
         * Create a prefix strip rewrite rule.
         * <pre>
         * RewriteRule.prefix("/api", "")    // /api/users → /users
         * RewriteRule.prefix("/api", "/v2") // /api/users → /v2/users
         * </pre>
         */
        public static RewriteRule prefix(String prefix, String replacement) {
            return new RewriteRule(Type.PREFIX_REPLACE, prefix, replacement != null ? replacement : "", null, null);
        }

        /**
         * Create a regex rewrite rule.
         * <pre>
         * RewriteRule.regex("^/api/(.*)$", "/$1")  // /api/users → /users
         * </pre>
         */
        public static RewriteRule regex(String pattern, String replacement) {
            return new RewriteRule(Type.REGEX_REPLACE, pattern, replacement, Pattern.compile(pattern), null);
        }

        /**
         * Create a custom function rewrite rule.
         */
        public static RewriteRule function(RewriteFunction fn) {
            return new RewriteRule(Type.FUNCTION, null, null, null, fn);
        }

        /**
         * Apply this rewrite rule to the given path.
         *
         * @param path original request path
         * @return rewritten path, or original path if rule doesn't match
         */
        public String apply(String path) {
            if (path == null) return null;
            switch (type) {
                case PREFIX_REPLACE:
                    if (path.startsWith(from)) {
                        return to + path.substring(from.length());
                    }
                    return path;
                case REGEX_REPLACE:
                    return compiledPattern.matcher(path).replaceAll(to);
                case FUNCTION:
                    return function.rewrite(path);
                default:
                    return path;
            }
        }
    }
}

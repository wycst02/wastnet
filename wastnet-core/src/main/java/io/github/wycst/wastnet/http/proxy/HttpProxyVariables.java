package io.github.wycst.wastnet.http.proxy;

import io.github.wycst.wastnet.http.HttpHeaderNormalized;
import io.github.wycst.wastnet.http.HttpRequest;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;

/**
 * Built-in proxy variables registry, similar to Nginx variables.
 * <p>
 * Supports Nginx-style variable references in header values:
 * <ul>
 *   <li>{@code $remote_addr} - client IP address</li>
 *   <li>{@code $remote_port} - client port</li>
 *   <li>{@code $host} - client original Host header</li>
 *   <li>{@code $scheme} - request scheme (http/https)</li>
 *   <li>{@code $request_uri} - original request URI</li>
 *   <li>{@code $query_string} - query string (after ?)</li>
 *   <li>{@code $server_addr} - server IP address</li>
 *   <li>{@code $server_port} - server port</li>
 * </ul>
 * <p>
 * Usage:
 * <pre>
 * config.addHeader("X-Real-IP", "$remote_addr");
 * config.addHeader("X-Forwarded-Proto", "$scheme");
 * </pre>
 */
final class HttpProxyVariables {

    static final Map<String, HttpProxyConfig.HeaderValueResolver> BUILTINS = new HashMap<>();

    static {
        // $remote_addr - client IP address
        BUILTINS.put("$remote_addr", request -> {
            InetSocketAddress addr = request.getRemoteAddress();
            return addr != null ? addr.getAddress().getHostAddress() : "";
        });

        // $remote_port - client port
        BUILTINS.put("$remote_port", request -> {
            InetSocketAddress addr = request.getRemoteAddress();
            return addr != null ? String.valueOf(addr.getPort()) : "";
        });

        // $host - client original Host header
        BUILTINS.put("$host", request -> {
            String host = request.getHeader(HttpHeaderNormalized.getHost(), true);
            return host != null ? host : "";
        });

        // $scheme - request scheme (http / https)
        BUILTINS.put("$scheme", HttpRequest::getScheme);

        // $request_uri - decoded request URI (without query parameters)
        BUILTINS.put("$request_uri", HttpRequest::getRequestUri);

        // $uri - raw request URI (includes query parameters)
        BUILTINS.put("$uri", HttpRequest::getUri);

        // $query_string - query string (after ?)
        BUILTINS.put("$query_string", request -> {
            String queryString = request.getQueryString();
            return queryString != null ? queryString : "";
        });

        // $server_addr - server IP address
        BUILTINS.put("$server_addr", request -> {
            InetSocketAddress addr = request.getServerAddress();
            return addr != null ? addr.getAddress().getHostAddress() : "";
        });

        // $server_port - server port
        BUILTINS.put("$server_port", request -> {
            InetSocketAddress addr = request.getServerAddress();
            return addr != null ? String.valueOf(addr.getPort()) : "";
        });
    }

    private HttpProxyVariables() {
    }

    /**
     * Check if a value string contains variable references ($xxx).
     *
     * @param value the value to check
     * @return true if value contains '$'
     */
    public static boolean containsVariable(String value) {
        return value != null && value.indexOf('$') > -1;
    }

    /**
     * Resolve a value that may contain variable references.
     * <p>
     * If the value is exactly a single variable (e.g. "$remote_addr"),
     * returns the variable value directly.
     * If the value contains variables mixed with literal text
     * (e.g. "$scheme://$host"), resolves all variables and concatenates.
     * If no variables found, returns the value as-is.
     *
     * @param value   the value template
     * @param request current HTTP request
     * @return resolved value
     */
    public static String resolve(String value, HttpRequest request) {
        if (value == null) return null;

        // Fast path: single variable (most common case)
        HttpProxyConfig.HeaderValueResolver singleProvider = BUILTINS.get(value);
        if (singleProvider != null) {
            return singleProvider.resolve(request);
        }

        // Slow path: mixed variables and literals
        if (!containsVariable(value)) return value;

        StringBuilder sb = new StringBuilder(value.length());
        int i = 0;
        while (i < value.length()) {
            char c = value.charAt(i);
            if (c == '$') {
                // Extract variable name ($ followed by word chars)
                int start = i++;
                while (i < value.length()) {
                    char nc = value.charAt(i);
                    if ((nc >= 'a' && nc <= 'z') || (nc >= 'A' && nc <= 'Z')
                            || (nc >= '0' && nc <= '9') || nc == '_') {
                        ++i;
                    } else {
                        break;
                    }
                }
                String varName = value.substring(start, i);
                HttpProxyConfig.HeaderValueResolver provider = BUILTINS.get(varName);
                if (provider != null) {
                    String resolved = provider.resolve(request);
                    sb.append(resolved != null ? resolved : "");
                } else {
                    // Unknown variable, keep as-is
                    sb.append(varName);
                }
            } else {
                sb.append(c);
                ++i;
            }
        }
        return sb.toString();
    }
}

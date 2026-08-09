/*
 * Copyright 2026, wangyunchao.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.wycst.wastnet.http.handler;

import io.github.wycst.wastnet.http.*;
import io.github.wycst.wastnet.socket.conf.SocketConf;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * static resource route.
 *
 * @author wangyc
 */
public class HttpResourceRoute implements HttpRoute {

    // Only symlinks pointing outside docBase can bypass the string traversal check;
    // static dirs in production should not contain symlinks (normally they don't).
    private final String docBase;
    final String routePath;  // package-private for HttpRouterHandler
    private final int filePathOffset;
    private final File defaultFile;
    private final byte[] notFoundBytes;
    private byte[] notAllowedBytes;
    private boolean strictMode = true;
    private boolean cacheEnabled = true;
    private String defaultCacheControl = "max-age=0, must-revalidate";
    private final Map<String, String> mimeCacheControlRules = new HashMap<String, String>();
    private String[] earlyHintLinks;
    private String basePath;  // context path for $base_path replacement in early hints

    /**
     * Create a resource handler serving files from {@code docBase} at route {@code "/"}.
     *
     * @param docBase the static file root; request paths are resolved under it
     */
    public HttpResourceRoute(String docBase) {
        this("/", docBase, findDefaultFile(docBase, "index.html", "index.htm"));
    }

    /**
     * Create a resource handler with a custom default file.
     *
     * @param docBase      the static file root; request paths are resolved under it
     * @param defaultFile  fallback file for root requests (index.html equivalent)
     */
    public HttpResourceRoute(String docBase, File defaultFile) {
        this("/", docBase, defaultFile);
    }

    /**
     * Create a resource handler at a custom route path.
     *
     * @param routePath the URL path prefix for this handler (e.g. {@code "/static"})
     * @param docBase   the static file root; request paths are resolved under it
     */
    public HttpResourceRoute(String routePath, String docBase) {
        this(routePath, docBase, findDefaultFile(docBase, "index.html", "index.htm"));
    }

    /**
     * Full constructor for a resource handler.
     *
     * @param routePath    the URL path prefix for this handler
     * @param docBase      the static file root; request paths are resolved under it
     * @param defaultFile  fallback file for root requests, or {@code null} to disable
     */
    public HttpResourceRoute(String routePath, String docBase, File defaultFile) {
        this.routePath = routePath;
        this.filePathOffset = routePath.length();
        this.docBase = docBase;
        this.defaultFile = defaultFile;
        this.notFoundBytes = "404 Not Found".getBytes();
        this.notAllowedBytes = HttpStatus.METHOD_NOT_ALLOWED.text.getBytes();
    }

    /**
     * Set the base path for {@code $base_path} placeholder substitution in early hint links.
     * Called by HttpRouterHandler to pass the context path.
     */
    HttpResourceRoute setBasePath(String basePath) {
        this.basePath = basePath;
        resolveEarlyHintBase();
        return this;
    }

    private void resolveEarlyHintBase() {
        if (earlyHintLinks == null || basePath == null) return;
        for (int i = 0; i < earlyHintLinks.length; ++i) {
            earlyHintLinks[i] = earlyHintLinks[i].replace("$base_path", basePath);
        }
    }

    /**
     * Set custom response body for 405 Method Not Allowed.
     */
    public HttpResourceRoute notAllowedBody(String body) {
        this.notAllowedBytes = body.getBytes();
        return this;
    }

    /**
     * Enable or disable HTTP cache negotiation (304 Not Modified / ETag / Last-Modified).
     * <p>
     * Default is {@code true}. When disabled, the server always serves the full file
     * without checking {@code If-None-Match} or {@code If-Modified-Since} headers.
     */
    public HttpResourceRoute cacheEnabled(boolean cacheEnabled) {
        this.cacheEnabled = cacheEnabled;
        return this;
    }

    /**
     * Set the default {@code Cache-Control} header for files that do not match any
     * MIME-specific rule set via {@link #cacheControl(String, String)}.
     * <p>
     * Default is {@code "max-age=0, must-revalidate"} (weak cache, always revalidate via 304).
     * Set to a strong cache value like {@code "public, max-age=3600"} to enable browser caching.
     *
     * @param cacheControl the default Cache-Control value
     */
    public HttpResourceRoute defaultCacheControl(String cacheControl) {
        this.defaultCacheControl = cacheControl;
        return this;
    }

    /**
     * Set {@code Cache-Control} header value for files matching a specific MIME type.
     * <p>
     * Supports wildcard suffix: {@code "image/*"} matches {@code "image/png"}, {@code "image/jpeg"}, etc.
     * When a rule matches, it takes precedence over the default set via
     * {@link #defaultCacheControl(String)}.
     *
     * @param mimeType     MIME type pattern, e.g. {@code "text/html"}, {@code "image/*"}, {@code "font/*"}
     * @param cacheControl the full {@code Cache-Control} value, e.g. {@code "public, max-age=31536000, immutable"}
     */
    public HttpResourceRoute cacheControl(String mimeType, String cacheControl) {
        if (mimeType != null && !mimeType.isEmpty()) {
            mimeCacheControlRules.put(mimeType.toLowerCase(), cacheControl);
        }
        return this;
    }

    /**
     * Resolve {@code Cache-Control} value for the given MIME type.
     * <p>
     * Lookup order: exact MIME match → wildcard ({@code "type/*"}) → default.
     */
    private String resolveCacheControl(String mimeType) {
        if (!mimeCacheControlRules.isEmpty()) {
            String cc = mimeCacheControlRules.get(mimeType);
            if (cc != null) return cc;
            int slash = mimeType.indexOf('/');
            if (slash > -1) {
                cc = mimeCacheControlRules.get(mimeType.substring(0, slash + 1) + "*");
                if (cc != null) return cc;
            }
        }
        return defaultCacheControl;
    }

    /**
     * Disable strict mode: allow all HTTP methods (GET, POST, etc.).
     * <p>
     * By default, only GET is allowed; other methods return 405 Method Not Allowed.
     */
    public HttpResourceRoute allowAllMethods() {
        this.strictMode = false;
        return this;
    }

    /**
     * Configure 103 Early Hints (RFC 8297) for the default page (index.html).
     * <p>
     * When the default file (index.html) is requested, the server sends 103 Early Hints
     * with the specified Link headers before the final response, allowing the client
     * to preload static resources while the server processes the request.
     * <p>
     * The placeholder {@code $base_path} in each link header is replaced with the
     * resource handler's base path at configuration time.
     * <p>
     * Example:
     * <pre>
     * new HttpResourceRoute("/my-app", docBase)
     *     .earlyHints("&lt;$base_path/style.css&gt;; rel=preload; as=style");
     * // → &lt;/my-app/style.css&gt;; rel=preload; as=style
     * </pre>
     *
     * @param linkHeaders one or more Link header values
     * @return this handler for chaining
     */
    public HttpResourceRoute earlyHints(String... linkHeaders) {
        if (linkHeaders != null && linkHeaders.length > 0) {
            String[] list = new String[linkHeaders.length];
            int count = 0;
            for (String link : linkHeaders) {
                if (link != null && !link.isEmpty()) {
                    list[count++] = link;
                }
            }
            this.earlyHintLinks = count == 0 ? null : count == linkHeaders.length ? list : java.util.Arrays.copyOf(list, count);
            resolveEarlyHintBase();
        } else {
            this.earlyHintLinks = null;
        }
        return this;
    }

    private void sendEarlyHints(HttpResponse response) throws Throwable {
        for (String link : earlyHintLinks) {
            response.earlyHints(link);
        }
    }

    private static File findDefaultFile(String docBase, String... defaultFiles) {
        for (String file : defaultFiles) {
            File f = new File(docBase, file);
            if (f.isFile()) {
                return f;
            }
        }
        return null;
    }

    @Override
    public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
        if (strictMode && request.getMethod() != HttpMethod.GET) {
            response.status(HttpStatus.METHOD_NOT_ALLOWED)
                    .header(HttpHeaderNormalized.getAllow(), "GET, HEAD")
                    .body(notAllowedBytes);
            return;
        }
        File file;
        int len = path.length() - filePathOffset;
        if (len <= 1) {
            file = defaultFile;
        } else {
            int filePathOffset = this.filePathOffset, ch;
            while ((ch = path.charAt(filePathOffset)) == '/' || ch == '\\') {
                ++filePathOffset;
            }
            String rp = path.substring(filePathOffset);
            if(SocketConf.WINDOWS_PLATFORM) {
                if(rp.indexOf(':') > -1) {
                    response.status(HttpStatus.NOT_FOUND).write(notFoundBytes);
                    return;
                } 
                if(rp.contains("\\")) rp = rp.replace("\\", "/");
            }
            // String check chosen over getCanonicalPath() canonicalization:
            // canonical path resolution is extremely unstable on JDK 11+ (esp. Windows).
            if (rp.contains("../")) { // Security: prevent path traversal, rp is decoded path
                response.status(HttpStatus.NOT_FOUND).write(notFoundBytes);
                return;
            }
            file = new File(docBase, rp);
        }

        if (file == null || !file.isFile()) {
            response.status(HttpStatus.NOT_FOUND).write(notFoundBytes);
            return;
        }

        // Send 103 Early Hints when serving the default index page
        if (earlyHintLinks != null && file.equals(defaultFile)) {
            sendEarlyHints(response);
        }

        String mimeType = HttpHeaderUtils.getMimeTypeByFilename(file.getName(), HttpHeaderValues.APPLICATION_OCTET_STREAM);
        response.sendFile(file, cacheEnabled, resolveCacheControl(mimeType), mimeType);
    }
}
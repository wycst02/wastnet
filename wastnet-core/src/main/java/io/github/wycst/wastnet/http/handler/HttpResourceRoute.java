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

import io.github.wycst.wastnet.env.RuntimeEnv;
import io.github.wycst.wastnet.http.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * static resource route.
 *
 * @author wangyc
 */
public class HttpResourceRoute implements HttpRoute {

    private static final FileSystem DEFAULT_FS = FileSystems.getDefault(); // cached to avoid File.toPath() sync

    // Symlinks are not recommended in static resource dirs (a symlink can point outside docBase and bypass the path-traversal guard); avoid them in production.
    private final File docBaseDir;
    final String routePath;  // package-private for HttpRouterHandler
    private final int filePathOffset;
    private final File defaultFile;
    private final byte[] notFoundBytes;
    private byte[] notAllowedBytes;
    byte[] forbiddenBytes;
    private boolean strictMode = true;
    boolean checkSymlinks; // true = check symlinks at runtime (unless docBase scanned clean or allowed)
    private boolean cacheEnabled = true;
    String defaultCacheControl = "max-age=0, must-revalidate";
    private final Map<String, String> mimeCacheControlRules = new HashMap<String, String>();
    String[] earlyHintLinks;
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
        if (docBase == null || docBase.isEmpty()) {
            throw new IllegalArgumentException("docBase must not be null or empty");
        }
        this.docBaseDir = new File(docBase);
        this.filePathOffset = (this.routePath = routePath).length();
        this.defaultFile = defaultFile;
        this.notFoundBytes = HttpStatus.NOT_FOUND.text.getBytes(StandardCharsets.UTF_8);
        this.notAllowedBytes = HttpStatus.METHOD_NOT_ALLOWED.text.getBytes(StandardCharsets.UTF_8);
        this.forbiddenBytes = HttpStatus.FORBIDDEN.text.getBytes(StandardCharsets.UTF_8);
        this.checkSymlinks = docBaseDir.isDirectory() && scanHasSymlink(docBaseDir, System.currentTimeMillis() + SCAN_DEADLINE_MS);
    }

    /**
     * Set the base path for {@code $base_path} placeholder substitution in early hint links.
     * Called by HttpRouterHandler to pass the context path.
     */
    HttpResourceRoute basePath(String basePath) {
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
        this.notAllowedBytes = body.getBytes(StandardCharsets.UTF_8);
        return this;
    }

    /**
     * Set custom response body for 403 Forbidden.
     */
    public HttpResourceRoute forbiddenBody(String body) {
        this.forbiddenBytes = body.getBytes(StandardCharsets.UTF_8);
        return this;
    }

    /**
     * Enable or disable serving symlinks over HTTP; overrides the construction-time scan result.
     * <p>{@code true} allows symlinks to be read; {@code false} rejects them. Note: symlinks are not recommended in static resource dirs (a symlink can point outside docBase and bypass the path-traversal guard), so enabling this is discouraged.</p>
     *
     * @param allow true to allow symlinks, false to reject them
     * @return this handler for chaining
     */
    public HttpResourceRoute allowSymlinks(boolean allow) {
        this.checkSymlinks = !allow;
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

    // Scan all files under docBase for symlinks, recursing into every dir; bounded by a 3s deadline. Returns true if a file symlink is found or the deadline is hit (conservative). Dirs are not checked at scan time, but intermediate symlink dirs are rejected at runtime via hasSymlinkInPath.
    private static final long SCAN_DEADLINE_MS = 3000;

    static boolean scanHasSymlink(File dir, long deadline) {
        if (System.currentTimeMillis() > deadline) return true; // deadline exceeded: conservative
        File[] children = dir.listFiles();
        if (children == null) return false; // unreadable: not treated as symlink
        for (File child : children) {
            boolean symlink = Files.isSymbolicLink(child.toPath());
            if (symlink && !child.isDirectory()) return true; // file symlink found
            if (child.isDirectory() && scanHasSymlink(child, deadline)) return true; // recurse into dirs
        }
        return false;
    }

    /**
     * Check whether any component of the (already resolved) file path is a symlink,
     * walking from docBase down to the final file. A symlink directory can expose
     * files outside docBase, so every intermediate level must be rejected, not just
     * the final file.
     *
     * @param file the resolved file under docBase
     * @return true if any path component (incl. intermediate dirs) is a symlink
     */
    private boolean hasSymlinkInPath(File file) {
        File target = file;
        do {
            if (Files.isSymbolicLink(DEFAULT_FS.getPath(target.getPath()))) {
                return true;
            }
            if (docBaseDir.equals(target)) {
                return false; // reached docBase with no symlink component found
            }
        } while ((target = target.getParentFile()) != null);
        return true; // loop ended without reaching docBase => a symlink diverted the path out of docBase
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
            resolve_file_path: {
                int filePathOffset = this.filePathOffset, ch;
                while ((ch = path.charAt(filePathOffset)) == '/' || ch == '\\') {
                    if(++filePathOffset == path.length()) { // fall back to the index page for paths ending with only separators (e.g. "////")
                        file = defaultFile;
                        break resolve_file_path;
                    }
                }
                String rp = path.substring(filePathOffset);
                if(RuntimeEnv.WINDOWS_PLATFORM) {
                    if(rp.indexOf(':') > -1) {
                        response.status(HttpStatus.NOT_FOUND).write(notFoundBytes);
                        return;
                    }
                    if(rp.contains("\\")) rp = rp.replace("\\", "/");
                }
                // String check chosen over getCanonicalPath() canonicalization:
                // canonical path resolution is extremely unstable on JDK 11+ (esp. Windows).
                if (rp.contains("../")) { // Security: prevent path traversal, rp is decoded path
                    response.status(HttpStatus.NOT_FOUND).body(notFoundBytes);
                    return;
                }
                file = new File(docBaseDir, rp);
            }
        }

        if (file == null || !file.isFile()) {
            response.status(HttpStatus.NOT_FOUND).body(notFoundBytes);
            return;
        }

        // Reject if any path component (intermediate dirs or the final file) is a symlink; an intermediate symlink dir can expose files outside docBase.
        if (checkSymlinks && hasSymlinkInPath(file)) {
            response.status(HttpStatus.FORBIDDEN).body(forbiddenBytes);
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
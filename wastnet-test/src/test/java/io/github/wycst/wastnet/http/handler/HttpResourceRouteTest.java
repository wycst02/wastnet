package io.github.wycst.wastnet.http.handler;

import io.github.wycst.wastnet.http.HttpMethod;
import io.github.wycst.wastnet.http.HttpStatus;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;

/**
 * Unit tests for {@link HttpResourceRoute}.
 *
 * @author wangyc
 */
public class HttpResourceRouteTest {

    // ==================== Path traversal protection ====================

    @Test
    public void testHandleRejectsPathTraversal() throws Throwable {
        // Create a resource handler with a temp directory
        File tempDir = createTempDir("res-");
        try {
            HttpResourceRoute handler = new HttpResourceRoute("/", tempDir.getAbsolutePath());
            final HttpStatus[] capturedStatus = {null};
            handler.handle("/../etc/passwd", MockHttpTestBase.mockRequest(HttpMethod.GET),
                    MockHttpTestBase.mockResponse(capturedStatus, (String[]) null, null));
            Assertions.assertEquals(HttpStatus.NOT_FOUND, capturedStatus[0]);
        } finally {
            deleteDir(tempDir);
        }
    }

    @Test
    public void testHandleRejectsEncodedPathTraversal() throws Throwable {
        File tempDir = createTempDir("res-");
        try {
            HttpResourceRoute handler = new HttpResourceRoute("/", tempDir.getAbsolutePath());
            final HttpStatus[] capturedStatus = {null};
            handler.handle("/..\\..\\etc\\passwd", MockHttpTestBase.mockRequest(HttpMethod.GET),
                    MockHttpTestBase.mockResponse(capturedStatus, (String[]) null, null));
            Assertions.assertEquals(HttpStatus.NOT_FOUND, capturedStatus[0]);
        } finally {
            deleteDir(tempDir);
        }
    }

    @Test
    public void testHandleReturns405ForNonGetInStrictMode() throws Throwable {
        File tempDir = createTempDir("res-");
        try {
            HttpResourceRoute handler = new HttpResourceRoute("/", tempDir.getAbsolutePath());
            final HttpStatus[] capturedStatus = {null};
            handler.handle("/", MockHttpTestBase.mockRequest(HttpMethod.POST),
                    MockHttpTestBase.mockResponse(capturedStatus, (String[]) null, null));
            Assertions.assertEquals(HttpStatus.METHOD_NOT_ALLOWED, capturedStatus[0]);
        } finally {
            deleteDir(tempDir);
        }
    }

    @Test
    public void testHandleReturns404ForNonexistentFile() throws Throwable {
        File tempDir = createTempDir("res-");
        try {
            HttpResourceRoute handler = new HttpResourceRoute("/", tempDir.getAbsolutePath());
            final HttpStatus[] capturedStatus = {null};
            handler.handle("/nonexistent.html", MockHttpTestBase.mockRequest(HttpMethod.GET),
                    MockHttpTestBase.mockResponse(capturedStatus, (String[]) null, null));
            Assertions.assertEquals(HttpStatus.NOT_FOUND, capturedStatus[0]);
        } finally {
            deleteDir(tempDir);
        }
    }

    @Test
    public void testHandleServesExistingFile() throws Throwable {
        File tempDir = createTempDir("res-");
        try {
            // Create an actual file to serve
            File testFile = new File(tempDir, "test.txt");
            java.nio.file.Files.write(testFile.toPath(), "hello".getBytes());
            HttpResourceRoute handler = new HttpResourceRoute("/", tempDir.getAbsolutePath());
            final HttpStatus[] capturedStatus = {null};
            handler.handle("/test.txt", MockHttpTestBase.mockRequest(HttpMethod.GET),
                    MockHttpTestBase.mockResponse(capturedStatus, (String[]) null, null));
            Assertions.assertEquals(HttpStatus.OK, capturedStatus[0]);
        } finally {
            deleteDir(tempDir);
        }
    }

    @Test
    public void testHandleServesIndexHtmlByDefault() throws Throwable {
        File tempDir = createTempDir("res-");
        try {
            File indexFile = new File(tempDir, "index.html");
            java.nio.file.Files.write(indexFile.toPath(), "<html></html>".getBytes());
            HttpResourceRoute handler = new HttpResourceRoute("/", tempDir.getAbsolutePath());
            final HttpStatus[] capturedStatus = {null};
            handler.handle("/", MockHttpTestBase.mockRequest(HttpMethod.GET),
                    MockHttpTestBase.mockResponse(capturedStatus, (String[]) null, null));
            Assertions.assertEquals(HttpStatus.OK, capturedStatus[0]);
        } finally {
            deleteDir(tempDir);
        }
    }

    @Test
    public void testSendEarlyHintsWhenServingIndexHtml() throws Throwable {
        File tempDir = createTempDir("res-");
        try {
            File indexFile = new File(tempDir, "index.html");
            java.nio.file.Files.write(indexFile.toPath(), "<html></html>".getBytes());
            HttpResourceRoute handler = new HttpResourceRoute("/", tempDir.getAbsolutePath());
            handler.earlyHints("<style.css>; rel=preload; as=style");
            final HttpStatus[] capturedStatus = {null};
            handler.handle("/", MockHttpTestBase.mockRequest(HttpMethod.GET),
                    MockHttpTestBase.mockResponse(capturedStatus, (String[]) null, null));
            Assertions.assertEquals(HttpStatus.OK, capturedStatus[0]);
        } finally {
            deleteDir(tempDir);
        }
    }

    // ==================== earlyHints builder ====================

    @Test
    public void testEarlyHintsNullInput() {
        HttpResourceRoute handler = new HttpResourceRoute("/", ".");
        handler.earlyHints((String[]) null);
        Assertions.assertNull(getEarlyHintLinks(handler));
    }

    @Test
    public void testEarlyHintsEmptyArray() {
        HttpResourceRoute handler = new HttpResourceRoute("/", ".");
        handler.earlyHints();
        Assertions.assertNull(getEarlyHintLinks(handler));
    }

    @Test
    public void testEarlyHintsArrayWithEmptyString() {
        HttpResourceRoute handler = new HttpResourceRoute("/", ".");
        handler.earlyHints("");
        Assertions.assertNull(getEarlyHintLinks(handler));
    }

    @Test
    public void testEarlyHintsValidLinks() {
        HttpResourceRoute handler = new HttpResourceRoute("/", ".");
        handler.earlyHints("<a.css>; rel=preload", "<b.js>; rel=preload");
        String[] links = getEarlyHintLinks(handler);
        Assertions.assertNotNull(links);
        Assertions.assertEquals(2, links.length);
        Assertions.assertEquals("<a.css>; rel=preload", links[0]);
        Assertions.assertEquals("<b.js>; rel=preload", links[1]);
    }

    @Test
    public void testEarlyHintsFiltersNullAndEmptyEntries() {
        HttpResourceRoute handler = new HttpResourceRoute("/", ".");
        handler.earlyHints("<valid.css>; rel=preload", null, "", "<also.css>; rel=preload");
        String[] links = getEarlyHintLinks(handler);
        Assertions.assertNotNull(links);
        Assertions.assertEquals(2, links.length);
        Assertions.assertEquals("<valid.css>; rel=preload", links[0]);
        Assertions.assertEquals("<also.css>; rel=preload", links[1]);
    }

    @Test
    public void testEarlyHintsAllEntriesNullResultsInNull() {
        HttpResourceRoute handler = new HttpResourceRoute("/", ".");
        handler.earlyHints(null, "", null);
        Assertions.assertNull(getEarlyHintLinks(handler));
    }

    @Test
    public void testEarlyHintsWithoutBasePathLeavesDollarPlaceholder() {
        HttpResourceRoute handler = new HttpResourceRoute("/", ".");
        handler.earlyHints("<style.css>; rel=preload");
        // no setBasePath → resolveEarlyHintBase returns early (earlyHintLinks != null but basePath == null)
        String[] links = getEarlyHintLinks(handler);
        Assertions.assertEquals("<style.css>; rel=preload", links[0]);
    }

    private static String[] getEarlyHintLinks(HttpResourceRoute handler) {
        try {
            Field field = HttpResourceRoute.class.getDeclaredField("earlyHintLinks");
            field.setAccessible(true);
            return (String[]) field.get(handler);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static File createTempDir(String prefix) {
        File dir = new File(System.getProperty("java.io.tmpdir"), prefix + System.nanoTime());
        dir.mkdirs();
        return dir;
    }

    private static void deleteDir(File dir) {
        if (dir != null && dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
            dir.delete();
        }
    }

    // ==================== expires rules (resolveCacheControl coverage) ====================

    @Test
    public void testCacheControlExactMatch() throws Throwable {
        File tempDir = createTempDir("res-");
        try {
            File testFile = new File(tempDir, "style.css");
            java.nio.file.Files.write(testFile.toPath(), "body {}".getBytes());
            HttpResourceRoute handler = new HttpResourceRoute("/", tempDir.getAbsolutePath());
            handler.cacheControl("text/css", "public, max-age=31536000");
            final HttpStatus[] capturedStatus = {null};
            handler.handle("/style.css", MockHttpTestBase.mockRequest(HttpMethod.GET),
                    MockHttpTestBase.mockResponse(capturedStatus, (String[]) null, null));
            Assertions.assertEquals(HttpStatus.OK, capturedStatus[0]);
        } finally {
            deleteDir(tempDir);
        }
    }

    @Test
    public void testCacheControlWildcardMatch() throws Throwable {
        File tempDir = createTempDir("res-");
        try {
            File testFile = new File(tempDir, "photo.png");
            java.nio.file.Files.write(testFile.toPath(), new byte[100]);
            HttpResourceRoute handler = new HttpResourceRoute("/", tempDir.getAbsolutePath());
            handler.cacheControl("image/*", "public, max-age=2592000");
            final HttpStatus[] capturedStatus = {null};
            handler.handle("/photo.png", MockHttpTestBase.mockRequest(HttpMethod.GET),
                    MockHttpTestBase.mockResponse(capturedStatus, (String[]) null, null));
            Assertions.assertEquals(HttpStatus.OK, capturedStatus[0]);
        } finally {
            deleteDir(tempDir);
        }
    }

    @Test
    public void testCacheControlNoMatchUsesDefault() throws Throwable {
        File tempDir = createTempDir("res-");
        try {
            File testFile = new File(tempDir, "data.bin");
            java.nio.file.Files.write(testFile.toPath(), new byte[50]);
            HttpResourceRoute handler = new HttpResourceRoute("/", tempDir.getAbsolutePath());
            // rules exist but don't match text/html or application/octet-stream
            handler.cacheControl("image/*", "public, max-age=2592000");
            handler.cacheControl("text/css", "public, max-age=31536000");
            final HttpStatus[] capturedStatus = {null};
            handler.handle("/data.bin", MockHttpTestBase.mockRequest(HttpMethod.GET),
                    MockHttpTestBase.mockResponse(capturedStatus, (String[]) null, null));
            Assertions.assertEquals(HttpStatus.OK, capturedStatus[0]);
        } finally {
            deleteDir(tempDir);
        }
    }

    @Test
    public void testCacheControlWithEmptyMimeTypeIsIgnored() {
        HttpResourceRoute handler = new HttpResourceRoute("/", ".");
        // should not throw
        handler.cacheControl(null, "no-cache");
        handler.cacheControl("", "no-cache");
    }

    @Test
    public void testCacheEnabledFalseWithCacheControl() throws Throwable {
        File tempDir = createTempDir("res-");
        try {
            File testFile = new File(tempDir, "app.js");
            java.nio.file.Files.write(testFile.toPath(), "var x=1;".getBytes());
            HttpResourceRoute handler = new HttpResourceRoute("/", tempDir.getAbsolutePath());
            handler.cacheEnabled(false);
            handler.cacheControl("application/javascript", "public, max-age=31536000");
            final HttpStatus[] capturedStatus = {null};
            handler.handle("/app.js", MockHttpTestBase.mockRequest(HttpMethod.GET),
                    MockHttpTestBase.mockResponse(capturedStatus, (String[]) null, null));
            Assertions.assertEquals(HttpStatus.OK, capturedStatus[0]);
        } finally {
            deleteDir(tempDir);
        }
    }

    // ==================== constructor, notAllowedBody, path normalization ====================

    @Test
    public void testSingleArgConstructorWithIndexHtml() throws Throwable {
        File tempDir = createTempDir("res-");
        try {
            File indexFile = new File(tempDir, "index.html");
            java.nio.file.Files.write(indexFile.toPath(), "home".getBytes());
            HttpResourceRoute handler = new HttpResourceRoute(tempDir.getAbsolutePath());
            final HttpStatus[] capturedStatus = {null};
            handler.handle("/", MockHttpTestBase.mockRequest(HttpMethod.GET),
                    MockHttpTestBase.mockResponse(capturedStatus, (String[]) null, null));
            Assertions.assertEquals(HttpStatus.OK, capturedStatus[0]);
        } finally {
            deleteDir(tempDir);
        }
    }

    @Test
    public void testNotAllowedBodyCustomized() throws Throwable {
        File tempDir = createTempDir("res-");
        try {
            HttpResourceRoute handler = new HttpResourceRoute("/", tempDir.getAbsolutePath());
            handler.notAllowedBody("CUSTOM 405");
            final HttpStatus[] capturedStatus = {null};
            final byte[][] capturedBody = {null};
            handler.handle("/", MockHttpTestBase.mockRequest(HttpMethod.POST),
                    MockHttpTestBase.mockResponse(capturedStatus, (String[]) null, capturedBody));
            Assertions.assertEquals(HttpStatus.METHOD_NOT_ALLOWED, capturedStatus[0]);
            Assertions.assertNotNull(capturedBody[0]);
            Assertions.assertEquals("CUSTOM 405", new String(capturedBody[0]));
        } finally {
            deleteDir(tempDir);
        }
    }

    @Test
    public void testHandleWithDoubleSlashPrefix() throws Throwable {
        File tempDir = createTempDir("res-");
        try {
            File testFile = new File(tempDir, "test.txt");
            java.nio.file.Files.write(testFile.toPath(), "hello".getBytes());
            HttpResourceRoute handler = new HttpResourceRoute("/", tempDir.getAbsolutePath());
            final HttpStatus[] capturedStatus = {null};
            // Double // prefix triggers the while loop in handle()
            handler.handle("//test.txt", MockHttpTestBase.mockRequest(HttpMethod.GET),
                    MockHttpTestBase.mockResponse(capturedStatus, (String[]) null, null));
            Assertions.assertEquals(HttpStatus.OK, capturedStatus[0]);
        } finally {
            deleteDir(tempDir);
        }
    }
}

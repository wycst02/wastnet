package io.github.wycst.wastnet.http.handler;

import io.github.wycst.wastnet.http.HttpMethod;
import io.github.wycst.wastnet.http.HttpStatus;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

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
            Files.write(testFile.toPath(), "hello".getBytes());
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
            Files.write(indexFile.toPath(), "<html></html>".getBytes());
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
            Files.write(indexFile.toPath(), "<html></html>".getBytes());
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
        Assertions.assertNull(handler.earlyHintLinks);
    }

    @Test
    public void testEarlyHintsEmptyArray() {
        HttpResourceRoute handler = new HttpResourceRoute("/", ".");
        handler.earlyHints();
        Assertions.assertNull(handler.earlyHintLinks);
    }

    @Test
    public void testEarlyHintsArrayWithEmptyString() {
        HttpResourceRoute handler = new HttpResourceRoute("/", ".");
        handler.earlyHints("");
        Assertions.assertNull(handler.earlyHintLinks);
    }

    @Test
    public void testEarlyHintsValidLinks() {
        HttpResourceRoute handler = new HttpResourceRoute("/", ".");
        handler.earlyHints("<a.css>; rel=preload", "<b.js>; rel=preload");
        String[] links = handler.earlyHintLinks;
        Assertions.assertNotNull(links);
        Assertions.assertEquals(2, links.length);
        Assertions.assertEquals("<a.css>; rel=preload", links[0]);
        Assertions.assertEquals("<b.js>; rel=preload", links[1]);
    }

    @Test
    public void testEarlyHintsFiltersNullAndEmptyEntries() {
        HttpResourceRoute handler = new HttpResourceRoute("/", ".");
        handler.earlyHints("<valid.css>; rel=preload", null, "", "<also.css>; rel=preload");
        String[] links = handler.earlyHintLinks;
        Assertions.assertNotNull(links);
        Assertions.assertEquals(2, links.length);
        Assertions.assertEquals("<valid.css>; rel=preload", links[0]);
        Assertions.assertEquals("<also.css>; rel=preload", links[1]);
    }

    @Test
    public void testEarlyHintsAllEntriesNullResultsInNull() {
        HttpResourceRoute handler = new HttpResourceRoute("/", ".");
        handler.earlyHints(null, "", null);
        Assertions.assertNull(handler.earlyHintLinks);
    }

    @Test
    public void testEarlyHintsWithoutBasePathLeavesDollarPlaceholder() {
        HttpResourceRoute handler = new HttpResourceRoute("/", ".");
        handler.earlyHints("<style.css>; rel=preload");
        // no setBasePath → resolveEarlyHintBase returns early (earlyHintLinks != null but basePath == null)
        String[] links = handler.earlyHintLinks;
        Assertions.assertEquals("<style.css>; rel=preload", links[0]);
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

    // ==================== expires rules (resolveCacheControl) ====================

    @Test
    public void testCacheControlExactMatch() throws Throwable {
        File tempDir = createTempDir("res-");
        try {
            File testFile = new File(tempDir, "style.css");
            Files.write(testFile.toPath(), "body {}".getBytes());
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
            Files.write(testFile.toPath(), new byte[100]);
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
            Files.write(testFile.toPath(), new byte[50]);
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
            Files.write(testFile.toPath(), "var x=1;".getBytes());
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
            Files.write(indexFile.toPath(), "home".getBytes());
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
            Files.write(testFile.toPath(), "hello".getBytes());
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

    // ==================== constructor docBase validation ====================

    @Test
    public void testConstructorRejectsNullDocBase() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new HttpResourceRoute("/", null, null));
    }

    @Test
    public void testConstructorRejectsEmptyDocBase() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new HttpResourceRoute("/", "", null));
    }

    @Test
    public void testConstructorWithNonDirectoryDocBaseSkipsSymlinkScan() throws Exception {
        File tempDir = createTempDir("res-");
        try {
            File notADir = new File(tempDir, "afile.txt");
            Files.write(notADir.toPath(), "x".getBytes());
            // docBase is a regular file -> isDirectory() is false, so the scan is short-circuited
            HttpResourceRoute handler = new HttpResourceRoute("/", notADir.getAbsolutePath(), null);
            Assertions.assertFalse(handler.checkSymlinks);
        } finally {
            deleteDir(tempDir);
        }
    }

    // ==================== forbiddenBody / allowSymlinks / defaultCacheControl ====================

    @Test
    public void testForbiddenBodyCustomized() throws Exception {
        HttpResourceRoute handler = new HttpResourceRoute("/", ".");
        Assertions.assertSame(handler, handler.forbiddenBody("CUSTOM 403"));
        Assertions.assertEquals("CUSTOM 403", new String(handler.forbiddenBytes));
    }

    @Test
    public void testAllowSymlinksTogglesCheckSymlinks() throws Exception {
        HttpResourceRoute handler = new HttpResourceRoute("/", ".");
        handler.allowSymlinks(true);
        Assertions.assertFalse(handler.checkSymlinks, "allow=true must skip the runtime check");
        handler.allowSymlinks(false);
        Assertions.assertTrue(handler.checkSymlinks, "allow=false must enable the runtime check");
    }

    @Test
    public void testDefaultCacheControlCustomized() throws Exception {
        HttpResourceRoute handler = new HttpResourceRoute("/", ".");
        Assertions.assertSame(handler, handler.defaultCacheControl("public, max-age=3600"));
        Assertions.assertEquals("public, max-age=3600", handler.defaultCacheControl);
    }

    // ==================== hasSymlinkInPath ====================
    // handle() only calls hasSymlinkInPath when checkSymlinks is true. A clean docBase makes the
    // constructor compute false, so allowSymlinks(false) is used to force the check on.

    @Test
    public void testHasSymlinkInPathCleanFileIsServed() throws Throwable {
        File tempDir = createTempDir("res-");
        try {
            Files.write(new File(tempDir, "a.txt").toPath(), "hi".getBytes());
            HttpResourceRoute handler = new HttpResourceRoute("/", tempDir.getAbsolutePath());
            handler.allowSymlinks(false);
            final HttpStatus[] capturedStatus = {null};
            handler.handle("/a.txt", MockHttpTestBase.mockRequest(HttpMethod.GET),
                    MockHttpTestBase.mockResponse(capturedStatus, (String[]) null, null));
            Assertions.assertEquals(HttpStatus.OK, capturedStatus[0]);
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    public void testHasSymlinkInPathWalksIntermediateDirs() throws Throwable {
        File tempDir = createTempDir("res-");
        try {
            File sub = new File(tempDir, "sub");
            sub.mkdirs();
            Files.write(new File(sub, "a.txt").toPath(), "hi".getBytes());
            HttpResourceRoute handler = new HttpResourceRoute("/", tempDir.getAbsolutePath());
            handler.allowSymlinks(false);
            final HttpStatus[] capturedStatus = {null};
            handler.handle("/sub/a.txt", MockHttpTestBase.mockRequest(HttpMethod.GET),
                    MockHttpTestBase.mockResponse(capturedStatus, (String[]) null, null));
            Assertions.assertEquals(HttpStatus.OK, capturedStatus[0]);
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    public void testHasSymlinkInPathOutsideDocBaseIsForbidden() throws Throwable {
        File tempDir = createTempDir("res-");
        File outside = createTempDir("out-");
        try {
            File index = new File(outside, "index.html");
            Files.write(index.toPath(), "home".getBytes());
            // defaultFile lives outside docBase: walking up never reaches docBaseDir, so the
            // loop ends without a match and the path is rejected as 403.
            HttpResourceRoute handler = new HttpResourceRoute("/", tempDir.getAbsolutePath(), index);
            handler.allowSymlinks(false);
            handler.forbiddenBody("CUSTOM 403");
            final HttpStatus[] capturedStatus = {null};
            final byte[][] capturedBody = {null};
            handler.handle("/", MockHttpTestBase.mockRequest(HttpMethod.GET),
                    MockHttpTestBase.mockResponse(capturedStatus, (String[]) null, capturedBody));
            Assertions.assertEquals(HttpStatus.FORBIDDEN, capturedStatus[0]);
            Assertions.assertEquals("CUSTOM 403", new String(capturedBody[0]));
        } finally {
            deleteRecursively(tempDir);
            deleteRecursively(outside);
        }
    }

    // ==================== scanHasSymlink ====================
    // Reached reflectively: the constructor always passes now+3000, which can never exercise the
    // deadline branch, and a real symlink is needed for the "found" branches.

    @Test
    public void testScanHasSymlinkDeadlineExceeded() throws Exception {
        File tempDir = createTempDir("res-");
        try {
            // expired deadline -> conservative "assume symlink" to avoid an unbounded scan
            Assertions.assertTrue(HttpResourceRoute.scanHasSymlink(tempDir, System.currentTimeMillis() - 1));
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    public void testScanHasSymlinkUnreadableDirIsNotSymlink() throws Exception {
        File notADir = new File(System.getProperty("java.io.tmpdir"), "notadir-" + System.nanoTime());
        Files.write(notADir.toPath(), "x".getBytes());
        try {
            // listFiles() returns null for a non-directory -> treated as "no symlink"
            Assertions.assertFalse(HttpResourceRoute.scanHasSymlink(notADir, System.currentTimeMillis() + 3000));
        } finally {
            notADir.delete();
        }
    }

    @Test
    public void testScanHasSymlinkRecursesIntoSubDirs() throws Exception {
        File tempDir = createTempDir("res-");
        try {
            File sub = new File(tempDir, "sub");
            sub.mkdirs();
            Files.write(new File(sub, "a.txt").toPath(), "x".getBytes());
            Assertions.assertFalse(HttpResourceRoute.scanHasSymlink(tempDir, System.currentTimeMillis() + 3000));
        } finally {
            deleteRecursively(tempDir);
        }
    }

    // ==================== real symlink (needs OS privilege) ====================

    @Test
    public void testSymlinkIsRejectedWhenPresent() throws Throwable {
        File tempDir = createTempDir("res-");
        try {
            File target = new File(tempDir, "real.txt");
            Files.write(target.toPath(), "hi".getBytes());
            Path link = tempDir.toPath().resolve("link.txt");
            try {
                Files.createSymbolicLink(link, target.toPath());
            } catch (Exception e) {
                // Creating symlinks requires SeCreateSymbolicLinkPrivilege (admin / Developer Mode).
                Assumptions.abort("symlink creation not permitted on this host: " + e.getMessage());
            }
            HttpResourceRoute handler = new HttpResourceRoute("/", tempDir.getAbsolutePath());
            handler.allowSymlinks(false);
            final HttpStatus[] capturedStatus = {null};
            handler.handle("/link.txt", MockHttpTestBase.mockRequest(HttpMethod.GET),
                    MockHttpTestBase.mockResponse(capturedStatus, (String[]) null, null));
            Assertions.assertEquals(HttpStatus.FORBIDDEN, capturedStatus[0]);
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    public void testScanHasSymlinkDetectsSymlinks() throws Exception {
        File tempDir = createTempDir("res-");
        File clean = createTempDir("clean-");
        try {
            File target = new File(tempDir, "real.txt");
            Files.write(target.toPath(), "x".getBytes());
            File sub = new File(tempDir, "sub");
            sub.mkdirs();
            File subTarget = new File(sub, "inner.txt");
            Files.write(subTarget.toPath(), "y".getBytes());

            Path fileLink = tempDir.toPath().resolve("flink.txt");
            Path dirLink = tempDir.toPath().resolve("dlink");
            try {
                Files.createSymbolicLink(fileLink, target.toPath());   // file symlink
                Files.createSymbolicLink(dirLink, sub.toPath());       // directory symlink
            } catch (Exception e) {
                Assumptions.abort("symlink creation not permitted on this host: " + e.getMessage());
            }
            // file symlink -> symlink=true && !isDirectory -> return true
            Assertions.assertTrue(HttpResourceRoute.scanHasSymlink(tempDir, System.currentTimeMillis() + 3000));

            // symlink nested in a sub directory -> recursion reports true
            File csub = new File(clean, "sub");
            csub.mkdirs();
            try {
                Files.createSymbolicLink(csub.toPath().resolve("n.txt"), subTarget.toPath());
            } catch (Exception e) {
                Assumptions.abort("symlink creation not permitted on this host: " + e.getMessage());
            }
            Assertions.assertTrue(HttpResourceRoute.scanHasSymlink(clean, System.currentTimeMillis() + 3000));
        } finally {
            deleteRecursively(tempDir);
            deleteRecursively(clean);
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursively(c);
            }
        }
        file.delete();
    }
}

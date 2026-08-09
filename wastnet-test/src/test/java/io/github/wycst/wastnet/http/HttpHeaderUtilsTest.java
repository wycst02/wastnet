package io.github.wycst.wastnet.http;

import io.github.wycst.wastnet.http.HttpHeaderUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link HttpHeaderUtils}
 *
 * @author wangyc
 */
public class HttpHeaderUtilsTest {

    @Test
    public void testToTitleCaseNull() {
        Assertions.assertNull(HttpHeaderUtils.toTitleCase(null));
    }

    @Test
    public void testToTitleCaseEmpty() {
        Assertions.assertEquals("", HttpHeaderUtils.toTitleCase(""));
    }

    @Test
    public void testToTitleCaseStandard() {
        Assertions.assertEquals("Content-Type", HttpHeaderUtils.toTitleCase("content-type"));
        Assertions.assertEquals("Content-Type", HttpHeaderUtils.toTitleCase("Content-Type"));
        Assertions.assertEquals("Content-Length", HttpHeaderUtils.toTitleCase("content-length"));
        Assertions.assertEquals("User-Agent", HttpHeaderUtils.toTitleCase("user-agent"));
        Assertions.assertEquals("X-Forwarded-For", HttpHeaderUtils.toTitleCase("x-forwarded-for"));
    }

    @Test
    public void testToTitleCaseSingleWord() {
        Assertions.assertEquals("Host", HttpHeaderUtils.toTitleCase("host"));
        Assertions.assertEquals("Accept", HttpHeaderUtils.toTitleCase("accept"));
        Assertions.assertEquals("Cookie", HttpHeaderUtils.toTitleCase("cookie"));
    }

    @Test
    public void testToTitleCaseUnderscore() {
        Assertions.assertEquals("X-Custom-Header", HttpHeaderUtils.toTitleCase("X_CUSTOM_HEADER"));
    }

    @Test
    public void testGetMimeType() {
        Assertions.assertEquals("text/html", HttpHeaderUtils.getMimeType("html"));
        Assertions.assertEquals("text/html", HttpHeaderUtils.getMimeType("htm"));
        Assertions.assertEquals("text/plain", HttpHeaderUtils.getMimeType("txt"));
        Assertions.assertEquals("application/javascript", HttpHeaderUtils.getMimeType("js"));
        Assertions.assertEquals("application/json", HttpHeaderUtils.getMimeType("json"));
        Assertions.assertEquals("image/png", HttpHeaderUtils.getMimeType("png"));
        Assertions.assertEquals("image/jpeg", HttpHeaderUtils.getMimeType("jpg"));
        Assertions.assertEquals("image/jpeg", HttpHeaderUtils.getMimeType("jpeg"));
        Assertions.assertEquals("application/pdf", HttpHeaderUtils.getMimeType("pdf"));
    }

    @Test
    public void testGetMimeTypeCaseInsensitive() {
        Assertions.assertEquals("text/html", HttpHeaderUtils.getMimeType("HTML"));
        Assertions.assertEquals("text/html", HttpHeaderUtils.getMimeType("Html"));
    }

    @Test
    public void testGetMimeTypeNull() {
        Assertions.assertNull(HttpHeaderUtils.getMimeType(null));
    }

    @Test
    public void testGetMimeTypeEmpty() {
        Assertions.assertNull(HttpHeaderUtils.getMimeType(""));
    }

    @Test
    public void testGetMimeTypeUnknown() {
        Assertions.assertNull(HttpHeaderUtils.getMimeType("unknown_extension"));
    }

    @Test
    public void testGetMimeTypeByFilename() {
        Assertions.assertEquals("text/html", HttpHeaderUtils.getMimeTypeByFilename("index.html"));
        Assertions.assertEquals("application/javascript", HttpHeaderUtils.getMimeTypeByFilename("app.js"));
        Assertions.assertEquals("image/png", HttpHeaderUtils.getMimeTypeByFilename("logo.png"));
    }

    @Test
    public void testGetMimeTypeByFilenameWithPath() {
        Assertions.assertEquals("text/html", HttpHeaderUtils.getMimeTypeByFilename("/path/to/index.html"));
        Assertions.assertEquals("image/png", HttpHeaderUtils.getMimeTypeByFilename("/images/logo.png"));
    }

    @Test
    public void testGetMimeTypeByFilenameNoExtension() {
        Assertions.assertNull(HttpHeaderUtils.getMimeTypeByFilename("README"));
    }

    @Test
    public void testGetMimeTypeByFilenameNull() {
        Assertions.assertNull(HttpHeaderUtils.getMimeTypeByFilename(null));
    }

    @Test
    public void testGetMimeTypeByFilenameEmpty() {
        Assertions.assertNull(HttpHeaderUtils.getMimeTypeByFilename(""));
    }

    @Test
    public void testGetMimeTypeByFilenameHidden() {
        Assertions.assertNull(HttpHeaderUtils.getMimeTypeByFilename(".gitignore"));
    }

    @Test
    public void testDateHeaderValue() {
        String dateValue = HttpHeaderUtils.getDateHeaderValue(System.currentTimeMillis());
        Assertions.assertNotNull(dateValue);
        Assertions.assertTrue(dateValue.endsWith("GMT"), "Date should end with GMT");
        Assertions.assertEquals(29, dateValue.length());
    }

    @Test
    public void testDateHeaderBytes() {
        byte[] dateBytes = HttpHeaderUtils.getDateHeaderBytes(System.currentTimeMillis());
        Assertions.assertNotNull(dateBytes);
        Assertions.assertEquals(29, dateBytes.length);
        String str = new String(dateBytes);
        Assertions.assertTrue(str.endsWith("GMT"));
    }
}

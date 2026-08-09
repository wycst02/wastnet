package io.github.wycst.wastnet.http;

import io.github.wycst.wastnet.http.HttpBodyDecoder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;

/**
 * Unit tests for HttpBodyDecoder content type parsing logic.
 */
public class HttpBodyDecodeTest {

    /**
     * Create a test HttpBodyDecoder instance via anonymous subclass.
     */
    private HttpBodyDecoder createDecoder(String contentType) {
        return new HttpBodyDecoder(contentType) {
            @Override protected void doDecodeMultipartFields(byte[] boundaryBytes) {}
            @Override protected void decodeFormUrlencoded() {}
        };
    }

    @Test
    public void testDefaultCharset() {
        HttpBodyDecoder decoder = createDecoder("");
        Assertions.assertEquals("UTF-8", decoder.getCharset().name());
    }

    @Test
    public void testUtf8Charset() {
        HttpBodyDecoder decoder = createDecoder("text/html; charset=utf-8");
        Assertions.assertEquals("UTF-8", decoder.getCharset().name());
    }

    @Test
    public void testIso88591Charset() {
        HttpBodyDecoder decoder = createDecoder("text/html; charset=iso-8859-1");
        Charset charset = decoder.getCharset();
        Assertions.assertTrue(charset.name().contains("8859-1") || charset.name().contains("ISO"));
    }

    @Test
    public void testQuotedCharset() {
        HttpBodyDecoder decoder = createDecoder("text/html; charset=\"utf-8\"");
        Assertions.assertEquals("UTF-8", decoder.getCharset().name());
    }

    @Test
    public void testMultipartContentType() {
        HttpBodyDecoder decoder = createDecoder("multipart/form-data; boundary=----12345");
        Assertions.assertTrue(decoder.isMultipart());
    }

    @Test
    public void testNonMultipartContentType() {
        HttpBodyDecoder decoder = createDecoder("application/json");
        Assertions.assertFalse(decoder.isMultipart());
    }

    @Test
    public void testFormUrlencodedContentType() {
        HttpBodyDecoder decoder = createDecoder("application/x-www-form-urlencoded");
        Assertions.assertTrue(decoder.isFormUrlencoded());
    }

    @Test
    public void testNonFormUrlencodedContentType() {
        HttpBodyDecoder decoder = createDecoder("application/json");
        Assertions.assertFalse(decoder.isFormUrlencoded());
    }

    @Test
    public void testJsonContentType() {
        HttpBodyDecoder decoder1 = createDecoder("application/json");
        HttpBodyDecoder decoder2 = createDecoder("text/json");
        HttpBodyDecoder decoder3 = createDecoder("text/html");
        Assertions.assertTrue(decoder1.isJson());
        Assertions.assertTrue(decoder2.isJson());
        Assertions.assertFalse(decoder3.isJson());
    }

    @Test
    public void testOctetStreamContentType() {
        HttpBodyDecoder decoder1 = createDecoder("application/octet-stream");
        HttpBodyDecoder decoder2 = createDecoder("application/json");
        Assertions.assertTrue(decoder1.isOctetStream());
        Assertions.assertFalse(decoder2.isOctetStream());
    }

    @Test
    public void testContentTypeWithParameters() {
        HttpBodyDecoder decoder1 = createDecoder("multipart/form-data; boundary=----12345; charset=utf-8");
        HttpBodyDecoder decoder2 = createDecoder("application/json; charset=utf-8");
        Assertions.assertTrue(decoder1.isMultipart());
        Assertions.assertTrue(decoder2.isJson());
    }
}

package io.github.wycst.wastnet.http.h2;

import io.github.wycst.wastnet.http.HttpMessage;
import io.github.wycst.wastnet.http.HttpMethod;
import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpRequestDecoder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

/**
 * Integration-style tests for HttpRequestDecoder focusing on URI parsing.
 */
public class HttpRequestDecoderWithQueryTest {

    @Test
    public void testDecodeSimpleGet() {
        String raw = "GET /hello.txt HTTP/1.1\r\nHost: test\r\n\r\n";
        byte[] input = raw.getBytes(StandardCharsets.US_ASCII);

        HttpRequestDecoder decoder = new HttpRequestDecoder();
        decoder.decode(input, 0, input.length);

        HttpMessage msg = decoder.getResult();
        Assertions.assertTrue(msg.isHttpRequest());
        HttpRequest req = (HttpRequest) msg;
        Assertions.assertEquals(HttpMethod.GET, req.getMethod());
        Assertions.assertEquals("/hello.txt", req.getUri());
    }

    @Test
    public void testDecodeWithQueryString() {
        // Test URI with query parameters
        String raw = "GET /search?q=hello+world&lang=en HTTP/1.1\r\nHost: test\r\n\r\n";
        byte[] input = raw.getBytes(StandardCharsets.US_ASCII);

        HttpRequestDecoder decoder = new HttpRequestDecoder();
        decoder.decode(input, 0, input.length);

        HttpMessage msg = decoder.getResult();
        Assertions.assertTrue(msg.isHttpRequest());
        HttpRequest req = (HttpRequest) msg;
        Assertions.assertNotNull(req.getQueryString());
        Assertions.assertEquals("q=hello+world&lang=en", req.getQueryString());
    }
}

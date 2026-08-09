package io.github.wycst.wastnet.http;

import io.github.wycst.wastnet.http.HttpDecodedResponse;
import io.github.wycst.wastnet.http.HttpVersion;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Unit tests for {@link HttpDecodedResponse}.
 *
 * @author wangyc
 */
public class HttpDecodedResponseTest {

    @Test
    public void testBasicResponse() {
        Map<String, Object> headers = new HashMap<String, Object>();
        headers.put("server", "wastnet");
        HttpDecodedResponse response = new HttpDecodedResponse(
                HttpVersion.HTTP_1_1, 200, "OK", headers, new byte[0], 0L, null
        );
        Assertions.assertEquals(HttpVersion.HTTP_1_1, response.getVersion());
        Assertions.assertEquals(200, response.getStatusCode());
        Assertions.assertEquals("OK", response.getReasonPhrase());
        Assertions.assertEquals(0L, response.getContentLength());
        Assertions.assertNull(response.getContentType());
    }

    @Test
    public void testResponseWithBody() {
        byte[] body = "hello".getBytes();
        Map<String, Object> headers = new HashMap<String, Object>();
        HttpDecodedResponse response = new HttpDecodedResponse(
                HttpVersion.HTTP_1_0, 404, "Not Found", headers, body, body.length, "text/plain"
        );
        Assertions.assertEquals(HttpVersion.HTTP_1_0, response.getVersion());
        Assertions.assertEquals(404, response.getStatusCode());
        Assertions.assertArrayEquals(body, response.getBody());
        Assertions.assertEquals("text/plain", response.getContentType());
    }

    @Test
    public void testResponseHeadersAreCopied() {
        Map<String, Object> headers = new LinkedHashMap<String, Object>();
        headers.put("content-type", "application/json");
        headers.put("x-custom", "test");
        HttpDecodedResponse response = new HttpDecodedResponse(
                HttpVersion.HTTP_1_1, 200, "OK", headers, new byte[0], 0L, null
        );
        Map<String, Object> resultHeaders = response.getHeaders();
        Assertions.assertEquals("application/json", resultHeaders.get("content-type"));
        Assertions.assertEquals("test", resultHeaders.get("x-custom"));
        Assertions.assertEquals(2, resultHeaders.size());
    }

    @Test
    public void testResponseIsNotHttpRequest() {
        HttpDecodedResponse response = new HttpDecodedResponse(
                HttpVersion.HTTP_1_1, 200, "OK", new HashMap<String, Object>(), new byte[0], 0L, null
        );
        Assertions.assertFalse(response.isHttpRequest());
        Assertions.assertFalse(response.isUpgrade());
    }

    @Test
    public void testResponseWithContentLength() {
        Map<String, Object> headers = new HashMap<String, Object>();
        HttpDecodedResponse response = new HttpDecodedResponse(
                HttpVersion.HTTP_1_1, 200, "OK", headers, "data".getBytes(), 4L, "text/plain"
        );
        Assertions.assertEquals(4L, response.getContentLength());
    }

    @Test
    public void testNotStreamByDefault() {
        HttpDecodedResponse response = new HttpDecodedResponse(
                HttpVersion.HTTP_1_1, 200, "OK", new HashMap<String, Object>(), new byte[0], 0L, null
        );
        Assertions.assertFalse(response.isStream());
        Assertions.assertNull(response.getBodyStream());
    }

    @Test
    public void testStreamResponse() {
        // Stream response constructor requires all 9 params
        Map<String, Object> headers = new LinkedHashMap<String, Object>();
        headers.put("transfer-encoding", "chunked");
        byte[] body = new byte[0];
        HttpDecodedResponse response = new HttpDecodedResponse(
                HttpVersion.HTTP_1_1, 200, "OK",
                headers, body, 0L, null,
                true, null
        );
        Assertions.assertTrue(response.isStream());
    }
}

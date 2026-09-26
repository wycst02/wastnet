package io.github.wycst.wastnet.http;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.*;

/**
 * Unit tests for {@link HttpDefaultRequest}.
 *
 * @author wangyc
 */
public class HttpDefaultRequestTest {

    private static HttpDefaultRequest createSimpleGetRequest() {
        return createGetRequest("/test?name=value", "name", "value");
    }

    private static HttpDefaultRequest createGetRequest(String uri, String... params) {
        byte[] uriBytes = uri.getBytes();
        String requestUri = uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri;
        Map<String, List<String>> parameters = new LinkedHashMap<String, List<String>>();
        if (params != null) {
            for (int i = 0; i < params.length; i += 2) {
                String key = params[i];
                String val = i + 1 < params.length ? params[i + 1] : "";
                List<String> values = new ArrayList<String>();
                values.add(val);
                parameters.put(key, values);
            }
        }
        Map<String, Object> headers = new LinkedHashMap<String, Object>();
        headers.put("host", "localhost:8080");
        headers.put("user-agent", "TestAgent/1.0");
        headers.put("accept", "*/*");
        return new HttpDefaultRequest(
                HttpMethod.GET, uriBytes, requestUri, parameters,
                HttpVersion.HTTP_1_1, headers, new byte[0], 0L, null
        );
    }

    // ==================== Basic properties ====================

    @Test
    public void testMethod() {
        HttpDefaultRequest req = createSimpleGetRequest();
        Assertions.assertEquals(HttpMethod.GET, req.getMethod());
    }

    @Test
    public void testUri() {
        HttpDefaultRequest req = createSimpleGetRequest();
        Assertions.assertEquals("/test?name=value", req.getUri());
    }

    @Test
    public void testRequestUri() {
        HttpDefaultRequest req = createSimpleGetRequest();
        Assertions.assertEquals("/test", req.getRequestUri());
    }

    @Test
    public void testHttpVersion() {
        HttpDefaultRequest req = createSimpleGetRequest();
        Assertions.assertEquals(HttpVersion.HTTP_1_1, req.getHttpVersion());
    }

    @Test
    public void testCompleted() {
        HttpDefaultRequest req = createSimpleGetRequest();
        Assertions.assertTrue(req.isCompleted());
    }

    @Test
    public void testIsStream() {
        HttpDefaultRequest req = createSimpleGetRequest();
        Assertions.assertFalse(req.isStream());
    }

    @Test
    public void testIsBad() {
        HttpDefaultRequest req = createSimpleGetRequest();
        Assertions.assertFalse(req.isBad());
    }

    // ==================== Headers ====================

    @Test
    public void testGetHeader() {
        HttpDefaultRequest req = createSimpleGetRequest();
        Assertions.assertEquals("localhost:8080", req.getHeader("host"));
        Assertions.assertEquals("TestAgent/1.0", req.getHeader("user-agent"));
        Assertions.assertEquals("*/*", req.getHeader("accept"));
    }

    @Test
    public void testGetHeaderCaseInsensitive() {
        HttpDefaultRequest req = createSimpleGetRequest();
        Assertions.assertEquals("localhost:8080", req.getHeader("Host"));
        Assertions.assertEquals("TestAgent/1.0", req.getHeader("User-Agent"));
    }

    @Test
    public void testGetHeaderReturnsNullForMissing() {
        HttpDefaultRequest req = createSimpleGetRequest();
        Assertions.assertNull(req.getHeader("x-custom"));
    }

    @Test
    public void testContainsHeader() {
        HttpDefaultRequest req = createSimpleGetRequest();
        Assertions.assertTrue(req.containsHeader("host"));
        Assertions.assertFalse(req.containsHeader("x-nonexistent"));
    }

    @Test
    public void testGetHeaderNames() {
        HttpDefaultRequest req = createSimpleGetRequest();
        Set<String> names = req.getHeaderNames();
        Assertions.assertTrue(names.contains("host"));
        Assertions.assertTrue(names.contains("user-agent"));
        Assertions.assertTrue(names.contains("accept"));
    }

    @Test
    public void testGetFullHeader() {
        HttpDefaultRequest req = createSimpleGetRequest();
        List<String> value = req.getFullHeader("host");
        Assertions.assertEquals(1, value.size());
        Assertions.assertEquals("localhost:8080", value.get(0));
    }

    // ==================== URI Parameters ====================

    @Test
    public void testGetUriParameter() {
        HttpDefaultRequest req = createSimpleGetRequest();
        Assertions.assertEquals("value", req.getUriParameter("name"));
    }

    @Test
    public void testGetUriParameterReturnsNullForMissing() {
        HttpDefaultRequest req = createSimpleGetRequest();
        Assertions.assertNull(req.getUriParameter("nonexistent"));
    }

    @Test
    public void testGetQueryString() {
        HttpDefaultRequest req = createSimpleGetRequest();
        Assertions.assertEquals("name=value", req.getQueryString());
    }

    @Test
    public void testGetQueryStringReturnsNullForNoQuery() {
        HttpDefaultRequest req = createGetRequest("/path", new String[0]);
        Assertions.assertNull(req.getQueryString());
    }

    @Test
    public void testGetUriParameterWithMultipleValues() {
        byte[] uriBytes = "/search?q=a&q=b".getBytes();
        Map<String, List<String>> params = new LinkedHashMap<String, List<String>>();
        List<String> values = new ArrayList<String>();
        values.add("a");
        values.add("b");
        params.put("q", values);
        HttpDefaultRequest req = new HttpDefaultRequest(
                HttpMethod.GET, uriBytes, "/search", params,
                HttpVersion.HTTP_1_1, new HashMap<String, Object>(), new byte[0], 0L, null
        );
        Assertions.assertEquals("a", req.getUriParameter("q"));
        Assertions.assertEquals("q=a&q=b", req.getQueryString());
    }

    // ==================== Body ====================

    @Test
    public void testGetContentLength() {
        HttpDefaultRequest req = createSimpleGetRequest();
        Assertions.assertEquals(0L, req.getContentLength());
    }

    @Test
    public void testGetContentType() {
        HttpDefaultRequest req = createSimpleGetRequest();
        Assertions.assertNull(req.getContentType());
    }

    @Test
    public void testGetBodyData() {
        byte[] body = "request body".getBytes();
        Map<String, Object> headers = new HashMap<String, Object>();
        headers.put("content-type", "text/plain");
        HttpDefaultRequest req = new HttpDefaultRequest(
                HttpMethod.POST, "/submit".getBytes(), "/submit", new HashMap<String, List<String>>(),
                HttpVersion.HTTP_1_1, headers, body, body.length, "text/plain"
        );
        Assertions.assertArrayEquals(body, req.getBodyData());
    }

    @Test
    public void testBodyStream() {
        byte[] body = "stream body".getBytes();
        HttpDefaultRequest req = new HttpDefaultRequest(
                HttpMethod.POST, "/".getBytes(), "/", new HashMap<String, List<String>>(),
                HttpVersion.HTTP_1_1, new HashMap<String, Object>(), body, body.length, "text/plain"
        );
        InputStream is = req.bodyStream();
        Assertions.assertNotNull(is);
        byte[] readBuf = new byte[body.length];
        try {
            is.read(readBuf);
        } catch (Exception e) {
            Assertions.fail("stream read failed: " + e.getMessage());
        }
        Assertions.assertArrayEquals(body, readBuf);
    }

    // ==================== POST with body parameters ====================

    @Test
    public void testPostFormUrlencoded() {
        String contentType = "application/x-www-form-urlencoded";
        byte[] body = "username=john&password=secret".getBytes();
        Map<String, Object> headers = new HashMap<String, Object>();
        headers.put("content-type", contentType);
        HttpDefaultRequest req = new HttpDefaultRequest(
                HttpMethod.POST, "/login".getBytes(), "/login", new HashMap<String, List<String>>(),
                HttpVersion.HTTP_1_1, headers, body, body.length, contentType
        );
        // body parameters should be accessible
        Assertions.assertEquals("john", req.getBodyParameter("username"));
        Assertions.assertEquals("secret", req.getBodyParameter("password"));
    }

    // ==================== Multi-value header ====================

    @Test
    public void testGetFullHeaderWithListValue() {
        Map<String, Object> headers = new LinkedHashMap<String, Object>();
        List<String> values = new ArrayList<String>();
        values.add("en-US");
        values.add("zh-CN");
        headers.put("accept-language", values);
        HttpDefaultRequest req = new HttpDefaultRequest(
                HttpMethod.GET, "/".getBytes(), "/", new HashMap<String, List<String>>(),
                HttpVersion.HTTP_1_1, headers, new byte[0], 0L, null
        );
        List<String> full = req.getFullHeader("accept-language");
        Assertions.assertEquals(2, full.size());
        Assertions.assertEquals("en-US", full.get(0));
        Assertions.assertEquals("zh-CN", full.get(1));
    }

    // ==================== Parameter merging ====================

    @Test
    public void testGetParameterMergesUriAndBody() {
        // URI parameter + form body parameter with same name should merge
        // Note: URI takes precedence over body for getParameter (first value wins)
        String contentType = "application/x-www-form-urlencoded";
        byte[] body = "name=bodyName".getBytes();
        Map<String, List<String>> uriParams = new HashMap<String, List<String>>();
        List<String> uriValues = new ArrayList<String>();
        uriValues.add("uriName");
        uriParams.put("name", uriValues);
        Map<String, Object> headers = new HashMap<String, Object>();
        headers.put("content-type", contentType);
        HttpDefaultRequest req = new HttpDefaultRequest(
                HttpMethod.POST, "/path?name=uriName".getBytes(), "/path", uriParams,
                HttpVersion.HTTP_1_1, headers, body, body.length, contentType
        );
        // getParameter returns URI value first
        Assertions.assertEquals("uriName", req.getParameter("name"));
        // getParameterValues returns merged list
        List<String> allValues = req.getParameterValues("name");
        Assertions.assertEquals(2, allValues.size());
        Assertions.assertEquals("uriName", allValues.get(0));
        Assertions.assertEquals("bodyName", allValues.get(1));
    }

    @Test
    public void testPostMethod() {
        Map<String, Object> headers = new HashMap<String, Object>();
        headers.put("content-type", "application/json");
        byte[] body = "{\"key\":\"value\"}".getBytes();
        HttpDefaultRequest req = new HttpDefaultRequest(
                HttpMethod.POST, "/api/data".getBytes(), "/api/data",
                new HashMap<String, List<String>>(), HttpVersion.HTTP_1_1,
                headers, body, body.length, "application/json"
        );
        Assertions.assertEquals(HttpMethod.POST, req.getMethod());
        Assertions.assertEquals("application/json", req.getContentType());
        Assertions.assertFalse(req.isMultipart());
        Assertions.assertFalse(req.isFormUrlencoded());
        Assertions.assertTrue(req.isJson());
    }
}

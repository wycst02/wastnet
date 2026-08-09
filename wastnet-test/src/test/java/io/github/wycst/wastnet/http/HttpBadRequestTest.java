package io.github.wycst.wastnet.http;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link HttpBadRequest}.
 *
 * @author wangyc
 */
public class HttpBadRequestTest {

    @Test
    public void testDefaultBadRequest() {
        HttpBadRequest request = new HttpBadRequest();
        Assertions.assertTrue(request.isBad());
        Assertions.assertTrue(request.isHttpRequest());
        Assertions.assertEquals(HttpStatus.BAD_REQUEST, request.getStatus());
        Assertions.assertEquals(HttpStatus.BAD_REQUEST.code, request.getHttpStatusCode());
        Assertions.assertEquals(HttpStatus.BAD_REQUEST.text, request.getErrorMessage());
        Assertions.assertEquals(HttpStatus.BAD_REQUEST, request.getHttpStatus());
    }

    @Test
    public void testStatusBuilder() {
        HttpBadRequest request = new HttpBadRequest();
        request.status(HttpStatus.NOT_FOUND);
        Assertions.assertEquals(HttpStatus.NOT_FOUND.code, request.getHttpStatusCode());
        Assertions.assertEquals(HttpStatus.NOT_FOUND.text, request.getErrorMessage());
    }

    @Test
    public void testToString() {
        HttpBadRequest request = new HttpBadRequest();
        request.status(HttpStatus.NOT_FOUND);
        String str = request.toString();
        Assertions.assertTrue(str.contains("BadHttpRequest"), str);
        Assertions.assertTrue(str.contains("404"), str);
    }

    @Test
    public void test400BadRequestStatus() {
        HttpBadRequest request = new HttpBadRequest();
        Assertions.assertEquals(400, request.getHttpStatusCode());
    }

    @Test
    public void test408RequestTimeoutStatus() {
        HttpBadRequest request = new HttpBadRequest();
        request.status(HttpStatus.REQUEST_TIMEOUT);
        Assertions.assertEquals(408, request.getHttpStatusCode());
        Assertions.assertEquals("Request Timeout", request.getErrorMessage());
    }

    @Test
    public void test413RequestEntityTooLargeStatus() {
        HttpBadRequest request = new HttpBadRequest();
        request.status(HttpStatus.REQUEST_ENTITY_TOO_LARGE);
        Assertions.assertEquals(413, request.getHttpStatusCode());
    }

    @Test
    public void test414RequestUriTooLongStatus() {
        HttpBadRequest request = new HttpBadRequest();
        request.status(HttpStatus.REQUEST_URI_TOO_LONG);
        Assertions.assertEquals(414, request.getHttpStatusCode());
    }

    @Test
    public void test431HeaderTooLargeStatus() {
        HttpBadRequest request = new HttpBadRequest();
        request.status(HttpStatus.REQUEST_HEADER_FIELDS_TOO_LARGE);
        Assertions.assertEquals(431, request.getHttpStatusCode());
    }

    @Test
    public void test505HttpVersionNotSupported() {
        HttpBadRequest request = new HttpBadRequest();
        request.status(HttpStatus.HTTP_VERSION_NOT_SUPPORTED);
        Assertions.assertEquals(505, request.getHttpStatusCode());
    }
}

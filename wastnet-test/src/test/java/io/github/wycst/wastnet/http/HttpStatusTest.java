package io.github.wycst.wastnet.http;

import io.github.wycst.wastnet.http.HttpStatus;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link HttpStatus}
 *
 * @author wangyc
 */
public class HttpStatusTest {

    @Test
    public void testOf() {
        Assertions.assertSame(HttpStatus.OK, HttpStatus.of(200));
        Assertions.assertSame(HttpStatus.NOT_FOUND, HttpStatus.of(404));
        Assertions.assertSame(HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.of(500));
    }

    @Test
    public void testOfAllStandardCodes() {
        int[] codes = {
                100, 101, 102,
                200, 201, 202, 203, 204, 205, 206, 207,
                300, 301, 302, 303, 304, 305, 307, 308,
                400, 401, 402, 403, 404, 405, 406, 407, 408, 409, 410,
                411, 412, 413, 414, 415, 416, 417, 421, 422, 423, 424,
                425, 426, 428, 429, 431,
                500, 501, 502, 503, 504, 505, 506, 507, 508, 510, 511
        };
        for (int code : codes) {
            Assertions.assertNotNull(HttpStatus.of(code), "Expected non-null HttpStatus for code " + code);
            Assertions.assertEquals(code, HttpStatus.of(code).code);
        }
    }

    @Test
    public void testOfUnknownCode() {
        Assertions.assertNull(HttpStatus.of(99));
        Assertions.assertNull(HttpStatus.of(999));
        Assertions.assertNull(HttpStatus.of(-1));
    }
}

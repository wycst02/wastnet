package io.github.wycst.wastnet.exception;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SocketException}.
 */
public class ExceptionTest {

    @Test
    public void testSocketExceptionWithMessage() {
        SocketException ex = new SocketException("connection refused");
        Assertions.assertEquals("connection refused", ex.getMessage());
        Assertions.assertNull(ex.getCause());
    }

    @Test
    public void testSocketExceptionWithMessageAndCause() {
        RuntimeException cause = new RuntimeException("i/o error");
        SocketException ex = new SocketException("socket failed", cause);
        Assertions.assertEquals("socket failed", ex.getMessage());
        Assertions.assertSame(cause, ex.getCause());
    }

    @Test
    public void testSocketExceptionIsRuntimeException() {
        SocketException ex = new SocketException("test");
        Assertions.assertTrue(ex instanceof RuntimeException);
    }
}

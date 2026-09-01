package io.github.wycst.wastnet.http.h2;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Http2FrameType}.
 *
 * @author wangyc
 */
public class Http2FrameTypeTest {

    @Test
    public void testAllFrameTypes() {
        Assertions.assertEquals(10, Http2FrameType.values().length);
    }

    @Test
    public void testValueOfValidCodes() {
        Assertions.assertSame(Http2FrameType.DATA, Http2FrameType.valueOf(0x00));
        Assertions.assertSame(Http2FrameType.HEADERS, Http2FrameType.valueOf(0x01));
        Assertions.assertSame(Http2FrameType.PRIORITY, Http2FrameType.valueOf(0x02));
        Assertions.assertSame(Http2FrameType.RST_STREAM, Http2FrameType.valueOf(0x03));
        Assertions.assertSame(Http2FrameType.SETTINGS, Http2FrameType.valueOf(0x04));
        Assertions.assertSame(Http2FrameType.PUSH_PROMISE, Http2FrameType.valueOf(0x05));
        Assertions.assertSame(Http2FrameType.PING, Http2FrameType.valueOf(0x06));
        Assertions.assertSame(Http2FrameType.GOAWAY, Http2FrameType.valueOf(0x07));
        Assertions.assertSame(Http2FrameType.WINDOW_UPDATE, Http2FrameType.valueOf(0x08));
        Assertions.assertSame(Http2FrameType.CONTINUATION, Http2FrameType.valueOf(0x09));
    }

    @Test
    public void testValueOfNegativeCode() {
        Assertions.assertNull(Http2FrameType.valueOf(-1));
    }

    @Test
    public void testValueOfTooLargeCode() {
        Assertions.assertNull(Http2FrameType.valueOf(10));
    }
}

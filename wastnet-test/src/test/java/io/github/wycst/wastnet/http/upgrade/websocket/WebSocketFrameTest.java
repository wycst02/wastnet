package io.github.wycst.wastnet.http.upgrade.websocket;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure in-memory coverage tests for WebSocketFrame and WebSocketSimpleMessage.
 */
class WebSocketFrameTest {

    // ==================== WebSocketFrame ====================

    @Test
    void testTextOf() {
        WebSocketFrame frame = WebSocketFrame.textOf("hello");
        assertEquals(WebSocketFrame.FrameType.TEXT, frame.getType());
        assertTrue(frame.isFin());
        assertNotNull(frame.getData());
    }

    @Test
    void testBinaryOfWithData() {
        WebSocketFrame frame = WebSocketFrame.binaryOf(new byte[]{1, 2, 3});
        assertEquals(WebSocketFrame.FrameType.BINARY, frame.getType());
        assertTrue(frame.isFin());
        assertTrue(frame.getData().length > 3);
    }

    @Test
    void testBinaryOfNullPayload() {
        WebSocketFrame frame = WebSocketFrame.binaryOf(null);
        assertEquals(WebSocketFrame.FrameType.BINARY, frame.getType());
        assertTrue(frame.getData().length >= 2);
    }

    @Test
    void testMerge() {
        WebSocketFrame a = new WebSocketFrame(WebSocketFrame.FrameType.TEXT, "AB".getBytes(), false);
        WebSocketFrame b = new WebSocketFrame(WebSocketFrame.FrameType.CONTINUATION, "CD".getBytes(), true);
        WebSocketFrame merged = a.merge(b, true);
        assertEquals("ABCD", new String(merged.getData()));
        assertTrue(merged.isFin());
        assertEquals(WebSocketFrame.FrameType.TEXT, merged.getType());
    }

    @Test
    void testInterfaceFlags() {
        WebSocketFrame frame = new WebSocketFrame(WebSocketFrame.FrameType.CLOSE, new byte[0], true);
        assertFalse(frame.isHttpRequest());
        assertTrue(frame.isWebSocket());
        assertTrue(frame.isUpgrade());
    }

    // ==================== FrameType.valueOf ====================

    @Test
    void testFrameTypeValueOfContinuation() {
        assertSame(WebSocketFrame.FrameType.CONTINUATION, WebSocketFrame.FrameType.valueOf(0x0));
    }

    @Test
    void testFrameTypeValueOfText() {
        assertSame(WebSocketFrame.FrameType.TEXT, WebSocketFrame.FrameType.valueOf(0x1));
    }

    @Test
    void testFrameTypeValueOfBinary() {
        assertSame(WebSocketFrame.FrameType.BINARY, WebSocketFrame.FrameType.valueOf(0x2));
    }

    @Test
    void testFrameTypeValueOfClose() {
        assertSame(WebSocketFrame.FrameType.CLOSE, WebSocketFrame.FrameType.valueOf(0x8));
    }

    @Test
    void testFrameTypeValueOfPing() {
        assertSame(WebSocketFrame.FrameType.PING, WebSocketFrame.FrameType.valueOf(0x9));
    }

    @Test
    void testFrameTypeValueOfPong() {
        assertSame(WebSocketFrame.FrameType.PONG, WebSocketFrame.FrameType.valueOf(0xA));
    }

    @Test
    void testFrameTypeValueOfInvalid() {
        assertThrows(IllegalArgumentException.class, () -> WebSocketFrame.FrameType.valueOf(0x3));
    }

    // ==================== WebSocketSimpleMessage ====================

    @Test
    void testSimpleMessageTextOf() {
        WebSocketSimpleMessage msg = WebSocketSimpleMessage.textOf("hello");
        assertNotNull(msg);
    }

    @Test
    void testSimpleMessageBinaryOf() {
        WebSocketSimpleMessage msg = WebSocketSimpleMessage.binaryOf(new byte[]{1, 2});
        assertNotNull(msg);
    }
}

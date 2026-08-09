package io.github.wycst.wastnet.http.h2;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

/**
 * Unit tests for {@link Http2Frame}.
 *
 * @author wangyc
 */
public class Http2FrameTest {

    @Test
    public void testCreateFrameFromScratch() {
        Http2Frame frame = new Http2Frame(new byte[9], 0, 9, 10, 10, Http2FrameType.DATA, 0x01, 1);
        Assertions.assertEquals(Http2FrameType.DATA, frame.type);
        Assertions.assertEquals(1, frame.streamId);
        Assertions.assertEquals(0x01, frame.flags);
        Assertions.assertEquals(10, frame.payloadLength);
    }

    @Test
    public void testCreateDataFrame() {
        byte[] data = {0, 0, 5, 0, 0, 0, 0, 0, 3, 72, 101, 108, 108, 111};
        Http2Frame frame = new Http2Frame(data, 0, 9, 5, 5, Http2FrameType.DATA, 0, 3);
        Assertions.assertEquals(5, frame.payloadActualLength);
        Assertions.assertEquals(9, frame.payloadActualOffset);
        Assertions.assertEquals(14, frame.frameLength);
    }

    @Test
    public void testFromByteBuffer() {
        byte[] frameBytes = new byte[14];
        frameBytes[0] = 0; frameBytes[1] = 0; frameBytes[2] = 5;
        frameBytes[3] = 0x01;
        frameBytes[4] = 0x04;
        frameBytes[5] = 0; frameBytes[6] = 0; frameBytes[7] = 0; frameBytes[8] = 1;
        frameBytes[9] = 0x01; frameBytes[10] = 0x02;

        ByteBuffer buf = ByteBuffer.wrap(frameBytes);
        Http2Frame frame = Http2Frame.fromByteBuffer(buf).get(0);

        Assertions.assertNotNull(frame);
        Assertions.assertEquals(5, frame.payloadLength);
        Assertions.assertEquals(Http2FrameType.HEADERS, frame.type);
        Assertions.assertEquals(0x04, frame.flags);
        Assertions.assertEquals(1, frame.streamId);
    }

    @Test
    public void testHasFlag() {
        Http2Frame frame = new Http2Frame(new byte[9], 0, 9, 0, 0, Http2FrameType.DATA, 0x05, 0);
        Assertions.assertTrue(frame.hasFlag(0x01));
        Assertions.assertTrue(frame.hasFlag(0x04));
        Assertions.assertFalse(frame.hasFlag(0x08));
        Assertions.assertTrue(frame.hasFlags(0x01 | 0x04));
    }

    @Test
    public void testIsEndStream() {
        Http2Frame frame = new Http2Frame(new byte[9], 0, 9, 0, 0, Http2FrameType.DATA, 0x01, 0);
        Assertions.assertTrue(frame.isEndStream());
        Http2Frame frame2 = new Http2Frame(new byte[9], 0, 9, 0, 0, Http2FrameType.DATA, 0x00, 0);
        Assertions.assertFalse(frame2.isEndStream());
    }

    @Test
    public void testIsEndHeaders() {
        Http2Frame frame = new Http2Frame(new byte[9], 0, 9, 0, 0, Http2FrameType.HEADERS, 0x04, 0);
        Assertions.assertTrue(frame.isEndHeaders());
        Http2Frame frame2 = new Http2Frame(new byte[9], 0, 9, 0, 0, Http2FrameType.HEADERS, 0x00, 0);
        Assertions.assertFalse(frame2.isEndHeaders());
    }

    @Test
    public void testSettingsFrame() {
        byte[] frameBytes = new byte[15];
        frameBytes[0] = 0; frameBytes[1] = 0; frameBytes[2] = 6;
        frameBytes[3] = 0x04; // SETTINGS
        frameBytes[4] = 0x00;
        frameBytes[5] = 0; frameBytes[6] = 0; frameBytes[7] = 0; frameBytes[8] = 0;
        frameBytes[9] = 0; frameBytes[10] = 4; // SETTINGS_INITIAL_WINDOW_SIZE

        ByteBuffer buf = ByteBuffer.wrap(frameBytes);
        Http2Frame frame = Http2Frame.fromByteBuffer(buf).get(0);
        Assertions.assertEquals(Http2FrameType.SETTINGS, frame.type);
    }

    @Test
    public void testPingFrame() {
        byte[] frameBytes = new byte[17];
        frameBytes[2] = 8; // payload length = 8
        frameBytes[3] = 0x06; // PING
        frameBytes[4] = 0x00;

        ByteBuffer buf = ByteBuffer.wrap(frameBytes);
        Http2Frame frame = Http2Frame.fromByteBuffer(buf).get(0);
        Assertions.assertEquals(Http2FrameType.PING, frame.type);
        Assertions.assertEquals(8, frame.payloadLength);
    }

    @Test
    public void testGoAwayFrame() {
        byte[] frameBytes = new byte[17];
        frameBytes[3] = 0x07; // GOAWAY
        frameBytes[4] = 0x00;

        ByteBuffer buf = ByteBuffer.wrap(frameBytes);
        Http2Frame frame = Http2Frame.fromByteBuffer(buf).get(0);
        Assertions.assertEquals(Http2FrameType.GOAWAY, frame.type);
    }
}

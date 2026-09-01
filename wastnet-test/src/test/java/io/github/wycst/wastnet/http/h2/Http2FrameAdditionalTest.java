package io.github.wycst.wastnet.http.h2;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

/**
 * Additional tests for {@link Http2Frame} — for frame parsing edge cases,
 * hex dump formatting, and payload handling.
 *
 * @author wangyc
 */
public class Http2FrameAdditionalTest {

    // ==================== fromByteBuffer edge cases ====================

    @Test
    public void testFromByteBufferRstStream() {
        byte[] b = new byte[13]; // 9 header + 4 payload
        b[2] = 4; // payload length = 4
        b[3] = 0x03; // RST_STREAM
        b[8] = 0x01; // streamId=1
        ByteBuffer buf = ByteBuffer.wrap(b);
        Http2Frame frame = Http2Frame.fromByteBuffer(buf).get(0);
        Assertions.assertEquals(Http2FrameType.RST_STREAM, frame.type);
        Assertions.assertEquals(1, frame.streamId);
        Assertions.assertEquals(4, frame.payloadLength);
    }

    @Test
    public void testFromByteBufferPriority() {
        byte[] b = new byte[14]; // 9 header + 5 payload
        b[3] = 0x02; // PRIORITY
        b[8] = 0x05; // streamId=5
        ByteBuffer buf = ByteBuffer.wrap(b);
        Http2Frame frame = Http2Frame.fromByteBuffer(buf).get(0);
        Assertions.assertEquals(Http2FrameType.PRIORITY, frame.type);
        Assertions.assertEquals(5, frame.streamId);
    }

    @Test
    public void testFromByteBufferWindowUpdate() {
        byte[] b = new byte[13];
        b[3] = 0x08; // WINDOW_UPDATE
        b[8] = 0x03;
        ByteBuffer buf = ByteBuffer.wrap(b);
        Http2Frame frame = Http2Frame.fromByteBuffer(buf).get(0);
        Assertions.assertEquals(Http2FrameType.WINDOW_UPDATE, frame.type);
    }

    @Test
    public void testFromByteBufferContinuation() {
        byte[] b = new byte[13];
        b[3] = 0x09; // CONTINUATION
        b[4] = 0x04; // END_HEADERS
        b[8] = 0x07;
        ByteBuffer buf = ByteBuffer.wrap(b);
        Http2Frame frame = Http2Frame.fromByteBuffer(buf).get(0);
        Assertions.assertEquals(Http2FrameType.CONTINUATION, frame.type);
        Assertions.assertTrue(frame.isEndHeaders());
    }

    @Test
    public void testFromByteBufferPushPromise() {
        byte[] b = new byte[14];
        b[3] = 0x05; // PUSH_PROMISE
        b[8] = 0x02;
        ByteBuffer buf = ByteBuffer.wrap(b);
        Http2Frame frame = Http2Frame.fromByteBuffer(buf).get(0);
        Assertions.assertEquals(Http2FrameType.PUSH_PROMISE, frame.type);
    }

    @Test
    public void testFromByteBufferLargeStreamId() {
        byte[] frameBytes = new byte[14];
        frameBytes[3] = 0x01; // HEADERS
        frameBytes[4] = 0x04; // END_HEADERS
        frameBytes[5] = 0x7F;
        frameBytes[6] = (byte) 0xFF;
        frameBytes[7] = (byte) 0xFF;
        frameBytes[8] = (byte) 0xFF; // streamId = 0x7FFFFFFF

        ByteBuffer buf = ByteBuffer.wrap(frameBytes);
        Http2Frame frame = Http2Frame.fromByteBuffer(buf).get(0);
        Assertions.assertEquals(0x7FFFFFFF, frame.streamId);
    }

    @Test
    public void testFromByteBufferZeroStreamId() {
        byte[] b = new byte[9];
        b[3] = 0x04; // SETTINGS
        ByteBuffer buf = ByteBuffer.wrap(b);
        Http2Frame frame = Http2Frame.fromByteBuffer(buf).get(0);
        Assertions.assertEquals(0, frame.streamId);
    }

    @Test
    public void testFromByteBufferAllFrameTypes() {
        for (Http2FrameType ft : Http2FrameType.values()) {
            byte byteCode = ft.code;
            byte[] b = new byte[13];
            b[3] = byteCode;
            ByteBuffer buf = ByteBuffer.wrap(b);
            Http2Frame frame = Http2Frame.fromByteBuffer(buf).get(0);
            Assertions.assertEquals(ft, frame.type);
        }
    }

    // ==================== toHexDump ====================

    @Test
    public void testToHexDumpReturnsFormattedString() {
        byte[] frameBytes = new byte[14];
        frameBytes[3] = 0x01; // HEADERS
        frameBytes[4] = 0x04;
        frameBytes[8] = 0x01;
        frameBytes[9] = 'a';
        frameBytes[10] = 'b';

        Http2Frame frame = new Http2Frame(frameBytes, 0, 9, 5, 5, Http2FrameType.HEADERS, 0x04, 1);

        String dump = frame.toHexDump();
        Assertions.assertNotNull(dump);
        Assertions.assertTrue(dump.contains("HEADERS"));
        Assertions.assertTrue(dump.contains("streamId=1"));
        Assertions.assertTrue(dump.contains("flags=0x04"));
    }

    @Test
    public void testToHexDumpWithNullType() {
        byte[] data = new byte[9];
        Http2Frame frame = new Http2Frame(data, 0, 9, 0, 0, null, 0, 0);
        String dump = frame.toHexDump();
        Assertions.assertTrue(dump.contains("UNKNOWN"));
    }

    @Test
    public void testToHexDumpWithPayload() {
        byte[] data = new byte[12]; // 9 header + 3 payload
        data[3] = 0x00; // DATA
        data[8] = 0x01;
        data[9] = 'H';
        data[10] = 'i';
        data[11] = '!';

        Http2Frame frame = new Http2Frame(data, 0, 9, 3, 3, Http2FrameType.DATA, 0, 1);

        String dump = frame.toHexDump();
        Assertions.assertTrue(dump.contains("Payload:"));
        Assertions.assertTrue(dump.contains("486921")); // "Hi!" in hex
    }

    // ==================== static toHexDump ====================

    @Test
    public void testStaticHexDumpNullData() {
        Assertions.assertEquals("", Http2Frame.toHexDump(null));
        Assertions.assertEquals("", Http2Frame.toHexDump(new byte[0]));
        Assertions.assertEquals("", Http2Frame.toHexDump(null, 8));
        Assertions.assertEquals("", Http2Frame.toHexDump(new byte[0], 8));
    }

    @Test
    public void testStaticHexDumpWithData() {
        byte[] data = "Hello".getBytes();
        String dump = Http2Frame.toHexDump(data);
        Assertions.assertNotNull(dump);
        // Static dump is in formatted table: spaces between each hex byte
        Assertions.assertTrue(dump.contains("48"));
        Assertions.assertTrue(dump.contains("65"));
        Assertions.assertTrue(dump.contains("6c"));
        Assertions.assertTrue(dump.contains("6c"));
        Assertions.assertTrue(dump.contains("6f"));
        Assertions.assertTrue(dump.contains("Hello")); // ASCII column
    }

    @Test
    public void testStaticHexDumpWithCustomBytesPerLine() {
        byte[] data = "Hello World!".getBytes();
        String dump8 = Http2Frame.toHexDump(data, 8);
        String dump16 = Http2Frame.toHexDump(data, 16);
        Assertions.assertNotNull(dump8);
        Assertions.assertNotNull(dump16);
        // 8-byte mode should have headers with "00 01 02 03 04 05 06 07"
        Assertions.assertTrue(dump8.contains("07"));
    }

    // ==================== payload ====================

    @Test
    public void testPayloadSetsOffsetAndLength() {
        Http2Frame frame = new Http2Frame(new byte[9], 0, 9, 100, 100, Http2FrameType.DATA, 0, 0);
        Assertions.assertEquals(9, frame.payloadActualOffset);
        Assertions.assertEquals(100, frame.payloadActualLength);
    }

    @Test
    public void testDataFrameWithPaddedFlag() {
        byte[] b = new byte[14];
        b[3] = 0x00; // DATA
        b[4] = 0x09; // END_STREAM | PADDED
        b[8] = 0x01;

        ByteBuffer buf = ByteBuffer.wrap(b);
        Http2Frame frame = Http2Frame.fromByteBuffer(buf).get(0);
        Assertions.assertEquals(Http2FrameType.DATA, frame.type);
        Assertions.assertTrue(frame.hasFlag(Http2Frame.END_STREAM));
        Assertions.assertTrue(frame.hasFlag(Http2Frame.PADDED));
        Assertions.assertFalse(frame.hasFlag(Http2Frame.END_HEADERS));
    }

    @Test
    public void testSettingsWithAck() {
        byte[] b = new byte[9];
        b[3] = 0x04; // SETTINGS
        b[4] = 0x01; // ACK

        ByteBuffer buf = ByteBuffer.wrap(b);
        Http2Frame frame = Http2Frame.fromByteBuffer(buf).get(0);
        Assertions.assertEquals(Http2FrameType.SETTINGS, frame.type);
        Assertions.assertTrue(frame.hasFlag(Http2Frame.SETTINGS_ACK));
    }

    // ==================== toHexDump additional branches ====================

    @Test
    public void testToHexDumpZeroPayload() {
        byte[] data = new byte[9]; // header only, no payload
        data[3] = 0x00; // DATA
        data[8] = 0x01;

        Http2Frame frame = new Http2Frame(data, 0, 9, 0, 0, Http2FrameType.DATA, 0, 1);

        String dump = frame.toHexDump();
        Assertions.assertFalse(dump.contains("Payload:"), "should skip payload line when payloadLength=0");
    }

    @Test
    public void testToHexDumpWithNonPrintableChars() {
        byte[] data = new byte[14];
        data[3] = 0x00; // DATA
        data[8] = 0x01;
        data[9] = 0x00; // null byte (non-printable)
        data[10] = 0x1F; // control char
        data[11] = 0x7F; // DEL char
        data[12] = 0x20; // space (printable)
        data[13] = 'A';

        Http2Frame frame = new Http2Frame(data, 0, 9, 5, 5, Http2FrameType.DATA, 0, 1);

        String dump = frame.toHexDump();
        Assertions.assertTrue(dump.contains("00 "), "null byte hex");
        Assertions.assertTrue(dump.contains("1f "), "control char hex");
        Assertions.assertTrue(dump.contains("7f "), "DEL hex");
    }

    @Test
    public void testStaticHexDumpSingleByte() {
        // Small data triggers short path in toHexDump(byte[], int)
        byte[] data = {0x42};
        String dump = Http2Frame.toHexDump(data, 8);
        Assertions.assertTrue(dump.contains("42"));
    }

    @Test
    public void testHasFlagsNotAll() {
        Http2Frame frame = new Http2Frame(new byte[9], 0, 9, 0, 0, Http2FrameType.DATA, 0x01, 0);
        Assertions.assertFalse(frame.hasFlags(Http2Frame.END_STREAM | Http2Frame.END_HEADERS));
    }
}

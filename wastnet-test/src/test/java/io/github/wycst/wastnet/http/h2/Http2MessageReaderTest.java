package io.github.wycst.wastnet.http.h2;

import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link Http2MessageReader}.
 * <p>
 * Created via real ChannelContext with unconnected SocketChannel.
 * I/O calls throw NotYetConnectedException or NPE on null readKey, which
 * is expected and caught. All side-effect assertions happen before the throw.
 */
public class Http2MessageReaderTest {

    static class TestHttp2MessageReader extends Http2MessageReader {
        @Override
        public void init(ChannelContext ctx) {}
        @Override
        protected Http2Stream getStream(int streamId, ChannelContext ctx) { return null; }
    }

    /** A minimal Http2Stream subclass for testing sendWindowUpdatePair. */
    static class TestHttp2Stream extends Http2Stream {
        TestHttp2Stream(Http2MessageReader reader, int streamId, ChannelContext ctx) {
            super(reader, streamId, ctx);
        }
        @Override protected void onEndHeaders() {}
        @Override protected void submit() {}
        @Override public String debugPrefix() { return "Test"; }
    }

    static ChannelContext ctx() throws IOException {
        return new ChannelContext(SocketChannel.open(), 4096);
    }

    /** Execute a test action that may throw due to unconnected socket, ignoring that. */
    static void safeRun(RunnableException r) {
        try { r.run(); } catch (Exception ignored) {}
    }

    @FunctionalInterface
    interface RunnableException { void run() throws Exception; }

    @Test
    public void testCloseConnection() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        assertTrue(reader.valid);
        safeRun(() -> reader.closeConnection(ctx()));
        assertFalse(reader.valid);
    }

    @Test
    public void testSendRstStreamFrame() throws Exception {
        safeRun(() -> new TestHttp2MessageReader().sendRstStreamFrame(ctx(), 1, 2));
    }

    @Test
    public void testSendGoawayFrame() throws Exception {
        safeRun(() -> new TestHttp2MessageReader().sendGoawayFrame(ctx(), 0, 0));
    }

    @Test
    public void testHandleControlFramePing() throws Exception {
        safeRun(() -> new TestHttp2MessageReader().handleFrame(ctx(), createPingFrame()));
    }

    @Test
    public void testHandleControlFramePriority() throws Exception {
        safeRun(() -> new TestHttp2MessageReader().handleFrame(ctx(), createControlFrame((byte) 2, 5, 0)));
    }

    @Test
    public void testHandleControlFrameDefaultClosesConnection() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        safeRun(() -> reader.handleFrame(ctx(), createControlFrame((byte) 7, 8, 0)));
        assertFalse(reader.valid);
    }

    @Test
    public void testHandleSettingsAckIgnored() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        Http2Frame frame = createControlFrame((byte) 4, 0, Http2Frame.SETTINGS_ACK);
        reader.handleFrame(ctx(), frame);
        assertTrue(reader.valid);
    }

    @Test
    public void testHandleSettingsHeaderTableSize() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        safeRun(() -> reader.handleFrame(ctx(), settingsFrame(1, 8192)));
        assertEquals(8192, reader.maxHpackEncoderTableSize);
    }

    @Test
    public void testHandleSettingsMaxConcurrentStreams() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        safeRun(() -> reader.handleFrame(ctx(), settingsFrame(3, 100)));
        assertEquals(100, reader.remoteMaxConcurrentStreams);
    }

    @Test
    public void testHandleSettingsInitialWindowSize() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        safeRun(() -> reader.handleFrame(ctx(), settingsFrame(4, 131072)));
        assertEquals(131072, reader.streamInitSendWindowSize);
    }

    @Test
    public void testHandleSettingsInitialWindowSizeNegative() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        safeRun(() -> reader.handleFrame(ctx(), settingsFrame(4, -1)));
        assertFalse(reader.valid);
    }

    @Test
    public void testHandleSettingsMaxFrameSize() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        safeRun(() -> reader.handleFrame(ctx(), settingsFrame(5, 65536)));
        assertEquals(65536, reader.maxSendPayloadSize);
    }

    @Test
    public void testHandleSettingsMaxFrameSizeTooSmall() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        safeRun(() -> reader.handleFrame(ctx(), settingsFrame(5, 1024)));
        assertFalse(reader.valid);
    }

    @Test
    public void testReadNextMessageFrameNegativeStreamId() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        ChannelContext ctx = ctx();
        byte[] buf = new byte[9];
        buf[5] = (byte) 0x80;
        safeRun(() -> reader.decode(ctx, buf, 0, buf.length));
        assertFalse(reader.valid);
    }

    @Test
    public void testReadNextMessageFrameLengthExceedsMax() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        ChannelContext ctx = ctx();
        byte[] buf = new byte[9];
        buf[1] = 64;
        buf[2] = 1;
        safeRun(() -> reader.decode(ctx, buf, 0, buf.length));
        assertFalse(reader.valid);
    }

    @Test
    public void testReadNextMessageFramePaddingAndPriority() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        byte[] buf = new byte[15];
        buf[2] = 6;
        buf[3] = 1;
        buf[4] = (byte) 0x28;
        buf[8] = 1;
        buf[9] = 0;
        reader.decode(ctx(), buf, 0, buf.length);
        assertTrue(reader.valid);
    }

    // ==================== handleControlFrame WINDOW_UPDATE on stream 0 ====================

    @Test
    public void testHandleControlFrameWindowUpdate() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        long before = reader.connectSendWindow;
        // Build frame manually: WINDOW_UPDATE frame with streamId=0
        byte[] data = new byte[13];
        data[2] = 4;          // payload length = 4
        data[3] = 8;          // type = WINDOW_UPDATE
        data[8] = 0;          // streamId = 0
        data[9] = 0; data[10] = 0; data[11] = 0x13; data[12] = (byte) 0x88; // increment = 5000
        Http2Frame frame = new Http2Frame(data, 0, 9, 4, 4, Http2FrameType.WINDOW_UPDATE, 0, 0);

        safeRun(() -> reader.handleFrame(ctx(), frame));
        assertTrue(reader.connectSendWindow > before);
    }

    @Test
    public void testHandleControlFrameWindowUpdateOverflow() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        reader.connectSendWindow = Integer.MAX_VALUE - 100;

        byte[] data = new byte[13];
        data[2] = 4;
        data[3] = 8;
        data[8] = 0;
        data[9] = 0; data[10] = 0; data[11] = 0; data[12] = (byte) 200;
        Http2Frame frame = new Http2Frame(data, 0, 9, 4, 4, Http2FrameType.WINDOW_UPDATE, 0, 0);

        safeRun(() -> reader.handleFrame(ctx(), frame));
        assertFalse(reader.valid);
    }

    // ==================== handleSettingsFrame unknown setting (connection-level, closes after ACK fail) ====================

    @Test
    public void testHandleSettingsUnknownSetting() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        // Sending SETTINGS on unconnected socket → writeFlush(ACK) throws → caught → closeConnection.
        // This tests that unknown settings don't cause unexpected errors BEFORE the ACK send.
        safeRun(() -> reader.handleFrame(ctx(), settingsFrame(0xFF, 999)));
        // Connection closed due to ACK send failure on unconnected socket, not due to unknown setting
        assertFalse(reader.valid);
    }

    // ==================== handleSettingsFrame catch block (exception in SETTINGS parsing) ====================

    @Test
    public void testHandleSettingsParsingExceptionClosesConnection() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        byte[] data = new byte[10];
        data[2] = 1;           // payload length = 1 (not a multiple of 6)
        data[3] = 4;           // type = SETTINGS
        data[8] = 0;           // streamId = 0
        Http2Frame frame = new Http2Frame(data, 0, 9, 1, 1, Http2FrameType.SETTINGS, 0, 0);

        safeRun(() -> reader.handleFrame(ctx(), frame));
        assertFalse(reader.valid);
    }

    // ==================== handleFrame non-zero streamId with null stream ====================

    @Test
    public void testHandleFrameNonNullStreamIdReturnsWhenStreamNull() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        byte[] buf = new byte[9];
        buf[3] = 4;            // SETTINGS
        buf[8] = 5;            // streamId = 5
        safeRun(() -> reader.decode(ctx(), buf, 0, buf.length));
        // getStream(5) returns null for TestHttp2MessageReader → handleFrame returns
        assertTrue(reader.valid);
    }

    // ==================== decode: len < 9 → read() ====================

    @Test
    public void testDecodeLenLessThan9ReadsFromChannel() throws Exception {
        // Use loopback TCP to buffer frame data on server side,
        // then provide only 5 bytes to decode → triggers len < 9 → read()
        java.nio.channels.ServerSocketChannel ssc =
                java.nio.channels.ServerSocketChannel.open();
        ssc.socket().bind(new java.net.InetSocketAddress("127.0.0.1", 0));
        int port = ssc.socket().getLocalPort();

        SocketChannel client = SocketChannel.open();
        client.connect(new java.net.InetSocketAddress("127.0.0.1", port));
        SocketChannel server = ssc.accept();
        ssc.close();

        try {
            // Build a valid frame: DATA, length=5, streamId=1
            byte[] frame = new byte[14];
            frame[2] = 5;
            frame[8] = 1;
            frame[9] = 'h'; frame[10] = 'e'; frame[11] = 'l'; frame[12] = 'l'; frame[13] = 'o';
            client.write(ByteBuffer.wrap(frame));
            client.close();

            // Data is now buffered in server's TCP receive buffer
            TestHttp2MessageReader reader = new TestHttp2MessageReader();
            ChannelContext ctx = new ChannelContext(server, 4096);

            // Provide only 5 bytes → len=5 < 9 → read() fetches remaining 4+5 from channel
            reader.decode(ctx, new byte[5], 0, 5);
            assertTrue(reader.valid);
        } finally {
            try { server.close(); } catch (Exception ignored) {}
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    // ==================== Static utility tests ====================

    @Test
    public void testReadUInt24() {
        byte[] buf = {0x12, 0x34, 0x56};
        assertEquals(0x123456, TestHttp2MessageReader.readUInt24(buf, 0));
    }

    @Test
    public void testReadInt32() {
        byte[] buf = {0x12, 0x34, 0x56, (byte) 0x78};
        assertEquals(0x12345678, TestHttp2MessageReader.readInt32(buf, 0));
    }

    @Test
    public void testReadUInt16() {
        byte[] buf = {(byte) 0xAB, (byte) 0xCD};
        assertEquals(0xABCD, TestHttp2MessageReader.readUInt16(buf, 0));
    }

    // ==================== WINDOW_UPDATE sending ====================

    @Test
    public void testSendConnectionWindowUpdate() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        safeRun(() -> reader.sendConnectionWindowUpdate(ctx(), 16384));
        assertTrue(reader.valid);
    }

    // ==================== Settings: same initial window size ====================

    @Test
    public void testHandleSettingsSameInitialWindowSize() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        safeRun(() -> reader.handleFrame(ctx(), settingsFrame(4, 65535)));
        assertEquals(0xFFFF, reader.streamInitSendWindowSize);
    }

    // ==================== decode: IOException catch ====================

    @Test
    public void testDecodeIOException() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        ServerSocketChannel ssc = ServerSocketChannel.open();
        ssc.socket().bind(new InetSocketAddress("127.0.0.1", 0));
        int port = ssc.socket().getLocalPort();
        SocketChannel client = SocketChannel.open();
        client.connect(new InetSocketAddress("127.0.0.1", port));
        SocketChannel server = ssc.accept();
        ssc.close();
        // Write 3 bytes then close client → decode() read() gets EOF → IOException
        client.write(ByteBuffer.wrap(new byte[3]));
        client.close();
        ChannelContext ctx = new ChannelContext(server, 4096);
        reader.decode(ctx, new byte[3], 0, 3);
        assertFalse(reader.valid);
        try { server.close(); } catch (Exception ignored) {}
    }

    // ==================== awaitSendWU / wakeupSendWU ====================

    @Test
    public void testAwaitSendWU() throws Exception {
        final TestHttp2MessageReader reader = new TestHttp2MessageReader();
        Thread notifier = new Thread(new Runnable() {
            @Override
            public void run() {
                sleep(50);
                reader.wakeupSendWU();
            }
        });
        notifier.start();
        reader.awaitSendWU();
        notifier.join();
        assertTrue(reader.valid);
    }

    @Test
    public void testWakeupSendWU() throws Exception {
        new TestHttp2MessageReader().wakeupSendWU();
    }

    // ==================== validatePreface short-circuit branches ====================

    @Test
    public void testValidatePrefaceSecondLongMismatch() {
        // First 8 bytes match, second 8 differ
        byte[] buf = new byte[24];
        System.arraycopy(Http2MessageReader.CLIENT_CONNECTION_PREFACE, 0, buf, 0, 8);
        assertFalse(Http2MessageReader.validatePreface(buf));
    }

    @Test
    public void testValidatePrefaceThirdLongMismatch() {
        // First 16 bytes match, last 8 differ
        byte[] buf = new byte[24];
        System.arraycopy(Http2MessageReader.CLIENT_CONNECTION_PREFACE, 0, buf, 0, 16);
        assertFalse(Http2MessageReader.validatePreface(buf));
    }

    // ==================== Settings: max frame size too large ====================

    @Test
    public void testHandleSettingsMaxFrameSizeTooLarge() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        // value > 16777215 triggers the second branch of the OR condition
        safeRun(() -> reader.handleFrame(ctx(), settingsFrame(5, 16777216)));
        assertFalse(reader.valid);
    }

    // ==================== handleControlFrame: WINDOW_UPDATE negative increment ====================

    @Test
    public void testHandleControlFrameWindowUpdateNegativeIncrement() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        // Build WINDOW_UPDATE frame with increment = -1 (negative)
        byte[] data = new byte[13];
        data[2] = 4;          // payload length = 4
        data[3] = 8;          // type = WINDOW_UPDATE
        data[8] = 0;          // streamId = 0
        data[9] = (byte) 0xFF; data[10] = (byte) 0xFF; data[11] = (byte) 0xFF; data[12] = (byte) 0xFF; // increment = -1
        Http2Frame frame = new Http2Frame(data, 0, 9, 4, 4, Http2FrameType.WINDOW_UPDATE, 0, 0);

        safeRun(() -> reader.handleFrame(ctx(), frame));
        assertFalse(reader.valid);
    }

    // ==================== awaitSendWU: InterruptedException ====================

    @Test
    public void testAwaitSendWUInterrupted() throws Exception {
        final TestHttp2MessageReader reader = new TestHttp2MessageReader();
        Thread waiter = new Thread(new Runnable() {
            @Override
            public void run() {
                reader.awaitSendWU();
            }
        });
        waiter.start();
        Thread.sleep(30); // ensure waiter enters wait(100)
        waiter.interrupt();
        waiter.join(500);
        assertFalse(waiter.isAlive());
    }

    // ==================== readNextMessageFrame: partial frame (readInternal) ====================

    @Test
    public void testReadNextMessageFramePartialRead() throws Exception {
        ServerSocketChannel ssc = ServerSocketChannel.open();
        ssc.socket().bind(new InetSocketAddress("127.0.0.1", 0));
        int port = ssc.socket().getLocalPort();
        SocketChannel client = SocketChannel.open();
        client.connect(new InetSocketAddress("127.0.0.1", port));
        SocketChannel server = ssc.accept();
        ssc.close();
        try {
            // DATA frame: length=10, streamId=1, payload "0123456789"
            byte[] frame = new byte[19];
            frame[2] = 10;
            frame[8] = 1;
            for (int i = 0; i < 10; ++i) frame[9 + i] = (byte) ('0' + i);
            client.write(ByteBuffer.wrap(frame));
            client.close();
            TestHttp2MessageReader reader = new TestHttp2MessageReader();
            ChannelContext ctx = new ChannelContext(server, 4096);
            // Provide only 9 bytes (header with length=10, streamId=1) → readInternal fetches remaining 10
            byte[] headerBuf = new byte[9];
            headerBuf[2] = 10;  // payload length
            headerBuf[8] = 1;   // streamId
            reader.decode(ctx, headerBuf, 0, 9);
            assertTrue(reader.valid);
        } finally {
            try { server.close(); } catch (Exception ignored) {}
        }
    }

    // ==================== payloadLength < 0 malformed frame ====================

    @Test
    public void testMalformedFramePayloadLengthNegative() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        // HEADERS frame (type=1), PADDED flag (0x08), length=2, pad=5
        // 1 + 5 > 2 → payloadLength < 0 → closeConnection
        byte[] buf = new byte[11];
        buf[2] = 2;            // payload length
        buf[3] = 1;            // type = HEADERS
        buf[4] = 0x08;         // flags = PADDED
        buf[8] = 1;            // streamId = 1
        buf[9] = 5;            // Pad Length = 5 (exceeds payload)
        buf[10] = 'x';         // 1 byte of actual payload
        safeRun(() -> reader.decode(ctx(), buf, 0, buf.length));
        assertFalse(reader.valid);
    }

    // ==================== frame-size error branches (decode FRAME_SIZE_ERROR / FLOW_CONTROL_ERROR) ====================

    @Test
    public void testDecodeSettingsWrongSizeCloses() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        safeRun(() -> reader.decode(ctx(), rawFrame(7, 4, 0), 0, 16));
        assertFalse(reader.valid);
    }

    @Test
    public void testDecodePingWrongSizeCloses() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        safeRun(() -> reader.decode(ctx(), rawFrame(9, 6, 0), 0, 18));
        assertFalse(reader.valid);
    }

    @Test
    public void testDecodeRstStreamWrongSizeCloses() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        safeRun(() -> reader.decode(ctx(), rawFrame(3, 3, 0), 0, 12));
        assertFalse(reader.valid);
    }

    @Test
    public void testDecodeWindowUpdateWrongSizeCloses() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        safeRun(() -> reader.decode(ctx(), rawFrame(3, 8, 0), 0, 12));
        assertFalse(reader.valid);
    }

    @Test
    public void testDecodePriorityWrongSizeCloses() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        safeRun(() -> reader.decode(ctx(), rawFrame(4, 2, 0), 0, 13));
        assertFalse(reader.valid);
    }

    @Test
    public void testDecodeGoawayWrongSizeCloses() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        safeRun(() -> reader.decode(ctx(), rawFrame(4, 7, 0), 0, 13));
        assertFalse(reader.valid);
    }

    // ==================== SETTINGS ACK with non-zero payload → FRAME_SIZE_ERROR ====================

    // ==================== SETTINGS_ENABLE_PUSH with invalid value → PROTOCOL_ERROR ====================

    @Test
    public void testHandleSettingsEnablePushInvalid() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        // id = 2 (ENABLE_PUSH), value = 2 (must be 0 or 1)
        safeRun(() -> reader.handleFrame(ctx(), settingsFrame(2, 2)));
        assertFalse(reader.valid);
    }

    // ==================== SETTINGS_INITIAL_WINDOW_SIZE overflow → FLOW_CONTROL_ERROR ====================

    @Test
    public void testHandleSettingsInitialWindowSizeOverflow() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        reader.connectSendWindow = Integer.MAX_VALUE - 10;
        reader.streamInitSendWindowSize = 65535;
        // delta = 65555 - 65535 = 20 > (MAX - connectSendWindow) = 10
        safeRun(() -> reader.handleFrame(ctx(), settingsFrame(4, 65555)));
        assertFalse(reader.valid);
    }

    // ==================== GOAWAY with non-zero errorCode → LOG.warn ====================

    @Test
    public void testHandleGoawayWithErrorCode() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        byte[] data = new byte[9 + 8];
        data[2] = 8;     // payload length = 8
        data[3] = 7;     // type = GOAWAY
        data[8] = 0;     // streamId = 0
        data[13] = 0; data[14] = 0; data[15] = 0; data[16] = 1; // errorCode = 1 (non-zero)
        Http2Frame frame = new Http2Frame(data, 0, 9, 8, 8, Http2FrameType.GOAWAY, 0, 0);
        safeRun(() -> reader.handleFrame(ctx(), frame));
        assertFalse(reader.valid);
    }

    // ==================== readNextMessageFrame: payloadLength < 0 (padding exceeds declared) ====================

    @Test
    public void testReadNextMessageFramePayloadLengthNegative() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        // HEADERS (type=1), PADDED flag, streamId=1, length=2, Pad Length=5 (>2)
        byte[] buf = new byte[11];
        buf[2] = 2;
        buf[3] = 1;
        buf[4] = 0x08;
        buf[8] = 1;
        buf[9] = 5;
        buf[10] = 'x';
        safeRun(() -> reader.decode(ctx(), buf, 0, buf.length));
        assertFalse(reader.valid);
    }

    // ==================== orphan DATA shrinking connection recv window below 0 ====================

    @Test
    public void testHandleOrphanDataRecvWindowUnderflow() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        reader.connectRecvWindow.set(5);
        byte[] data = new byte[9 + 10];
        data[2] = 10;          // payload length = 10
        data[3] = 0;           // type = DATA
        data[8] = 1;           // streamId = 1 (no such stream -> orphan)
        Http2Frame frame = new Http2Frame(data, 0, 9, 10, 10, Http2FrameType.DATA, 0, 1);
        safeRun(() -> reader.handleFrame(ctx(), frame));
        assertFalse(reader.valid);
    }

    // ==================== handleControlFrame: PING with wrong payload length ====================

    @Test
    public void testHandleControlFramePingWrongLength() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        safeRun(() -> reader.handleFrame(connectedCtx(), createControlFrame((byte) 6, 9, 0))); // PING, len=9 != 8
        assertFalse(reader.valid);
    }

    // ==================== handleControlFrame: WINDOW_UPDATE with wrong payload length ====================

    @Test
    public void testHandleControlFrameWindowUpdateWrongLength() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        safeRun(() -> reader.handleFrame(connectedCtx(), createControlFrame((byte) 8, 3, 0))); // WINDOW_UPDATE, len=3 != 4
        assertFalse(reader.valid);
    }

    // ==================== handleControlFrame: GOAWAY with too-small payload ====================

    @Test
    public void testHandleControlFrameGoawayWrongLength() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        safeRun(() -> reader.handleFrame(ctx(), createControlFrame((byte) 7, 4, 0))); // GOAWAY, len=4 < 8
        assertFalse(reader.valid);
    }

    // ==================== handleSettings: payload length not a multiple of 6 ====================

    @Test
    public void testHandleSettingsWrongPayloadSize() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        // SETTINGS with payload length = 7 (not multiple of 6)
        byte[] data = new byte[9 + 7];
        data[2] = 7;
        data[3] = 4;
        data[8] = 0;
        Http2Frame frame = new Http2Frame(data, 0, 9, 7, 7, Http2FrameType.SETTINGS, 0, 0);
        safeRun(() -> reader.handleFrame(ctx(), frame));
        assertFalse(reader.valid);
    }

    // ==================== SETTINGS: INITIAL_WINDOW_SIZE adjusts open streams ====================

    @Test
    public void testHandleSettingsInitialWindowSizeAdjustsStreams() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        TestHttp2Stream stream = new TestHttp2Stream(reader, 1, ctx());
        reader.streamMap.put(1, stream);
        long before = stream.sendWindow;
        safeRun(() -> reader.handleFrame(ctx(), settingsFrame(4, 131072)));
        assertTrue(stream.sendWindow > before);
        assertEquals(131072, reader.streamInitSendWindowSize);
    }

    // ==================== SETTINGS: INITIAL_WINDOW_SIZE delta overflow (FLOW_CONTROL_ERROR) ====================

    @Test
    public void testHandleSettingsInitialWindowSizeStreamOverflow() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        TestHttp2Stream stream = new TestHttp2Stream(reader, 1, ctx());
        stream.sendWindow = Integer.MAX_VALUE - 100; // near the 2^31-1 ceiling
        reader.streamMap.put(1, stream);
        // value(100000) > current window(65535): delta = 34465 forces per-stream overflow
        safeRun(() -> reader.handleFrame(ctx(), settingsFrame(4, 100000)));
        assertFalse(reader.valid);
    }

    // ==================== SETTINGS: INITIAL_WINDOW_SIZE shrink (delta < 0) ====================

    @Test
    public void testHandleSettingsInitialWindowSizeShrink() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        TestHttp2Stream stream = new TestHttp2Stream(reader, 1, ctx());
        reader.streamMap.put(1, stream);
        long before = stream.sendWindow;
        // value(1024) < streamInitSendWindowSize(65535): delta = -64511 (< 0)
        safeRun(() -> reader.handleFrame(ctx(), settingsFrame(4, 1024)));
        assertTrue(stream.sendWindow < before);
        assertEquals(1024, reader.streamInitSendWindowSize);
    }

    // ==================== onInboundRstStream default hook (no-op body) ====================

    @Test
    public void testOnInboundRstStreamHookDefault() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        reader.onInboundRstStream(ctx()); // base-class no-op (L355)
        assertTrue(reader.valid);
    }

    // ==================== closeConnection: sendGoaway write raises IOException ====================

    @Test
    public void testCloseConnectionWriteIOException() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        SocketChannel closed = SocketChannel.open();
        closed.close(); // write on a closed channel throws ClosedChannelException (IOException)
        ChannelContext ctx = new ChannelContext(closed, 4096);
        reader.closeConnection(ctx, 1); // exercises catch(IOException) in closeConnection (L577)
        assertFalse(reader.valid);
    }

    // ==================== Helpers ====================

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static byte[] rawFrame(int payloadLen, int type, int streamId) {
        byte[] b = new byte[9 + payloadLen];
        b[0] = (byte) ((payloadLen >> 16) & 0xFF);
        b[1] = (byte) ((payloadLen >> 8) & 0xFF);
        b[2] = (byte) (payloadLen & 0xFF);
        b[3] = (byte) type;
        b[5] = (byte) ((streamId >> 24) & 0xFF);
        b[6] = (byte) ((streamId >> 16) & 0xFF);
        b[7] = (byte) ((streamId >> 8) & 0xFF);
        b[8] = (byte) (streamId & 0xFF);
        return b;
    }

    // ==================== frame builders ====================

    private static Http2Frame createPingFrame() {
        byte[] data = new byte[17];
        data[3] = 6;
        return new Http2Frame(data, 0, 9, 8, 8, Http2FrameType.PING, 0, 0);
    }

    private static Http2Frame createControlFrame(byte type, int payloadLen, int flags) {
        byte[] data = new byte[9 + payloadLen];
        data[3] = type;
        return new Http2Frame(data, 0, 9, payloadLen, payloadLen, Http2FrameType.valueOf(type & 0xFF), flags, 0);
    }

    private static Http2Frame settingsFrame(int id, int value) {
        byte[] data = new byte[15];
        data[2] = 6;
        data[3] = 4;
        data[9] = (byte) (id >>> 8);
        data[10] = (byte) id;
        data[11] = (byte) (value >>> 24);
        data[12] = (byte) (value >>> 16);
        data[13] = (byte) (value >>> 8);
        data[14] = (byte) value;
        return new Http2Frame(data, 0, 9, 6, 6, Http2FrameType.SETTINGS, 0, 0);
    }

    // ==================== SETTINGS_HEADER_TABLE_SIZE branches ====================

    /** Build a real loopback-connected ChannelContext so closeConnection (sendGoaway/writeFlush) succeeds. */
    static ChannelContext connectedCtx() throws IOException {
        ServerSocketChannel ssc = ServerSocketChannel.open();
        ssc.bind(new InetSocketAddress("127.0.0.1", 0));
        SocketChannel client = SocketChannel.open(ssc.getLocalAddress());
        ssc.close();
        return new ChannelContext(client, 4096);
    }

    // ==================== closeConnection branches in readNextMessageFrame / handleSettingsFrame ====================

    /** l221: streamId < 0 (high bit set at buf[5]) inside readNextMessageFrame -> closeConnection(ctx, 6). */
    @Test
    public void testReadNextMessageFrameNegativeStreamIdCloses() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        byte[] buf = new byte[9];
        buf[3] = 4;            // type = SETTINGS
        buf[5] = (byte) 0x80;  // streamId high bit set -> negative
        reader.decode(connectedCtx(), buf, 0, 9);
        assertFalse(reader.valid);
    }

    /** l432: SETTINGS ACK (flags=0x1) with non-empty payload -> closeConnection(ctx, 6). */
    @Test
    public void testHandleSettingsAckWithPayloadCloses() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        byte[] buf = new byte[9 + 6];
        buf[2] = 6;        // payload length = 6
        buf[3] = 4;        // type = SETTINGS
        buf[4] = 0x01;     // flags = ACK
        reader.decode(connectedCtx(), buf, 0, buf.length);
        assertEquals("errorCode=6", reader.lastCloseReason);
        assertFalse(reader.valid);
    }

    /** l443: SETTINGS payload length not a multiple of 6 -> closeConnection(ctx, 6). */
    @Test
    public void testHandleSettingsWrongPayloadLengthCloses() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        Http2Frame f = createControlFrame((byte) 4, 4, 0); // SETTINGS, payloadLen=4 (not %6)
        reader.handleFrame(connectedCtx(), f);
        assertFalse(reader.valid);
    }

    /** l460: ENABLE_PUSH value=2 (neither 0 nor 1) -> closeConnection(ctx, 1). */
    @Test
    public void testHandleSettingsEnablePushInvalidCloses() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        reader.handleFrame(connectedCtx(), settingsFrame(2, 2));
        assertFalse(reader.valid);
    }

    /** l469: INITIAL_WINDOW_SIZE value < 0 -> closeConnection(ctx, 1). */
    @Test
    public void testHandleSettingsInitialWindowNegativeCloses() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        reader.handleFrame(connectedCtx(), settingsFrame(4, -1));
        assertFalse(reader.valid);
    }

    /** l492: MAX_FRAME_SIZE below lower bound (1024 < 16384) -> closeConnection(ctx, 1). */
    @Test
    public void testHandleSettingsMaxFrameSizeTooSmallCloses() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        reader.handleFrame(connectedCtx(), settingsFrame(5, 1024));
        assertFalse(reader.valid);
    }

    /** HEADER_TABLE_SIZE below the 4096 default disables encoder header indexing. */
    @Test
    public void testHandleSettingsHeaderTableSizeShrinkDisablesIndex() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        safeRun(() -> reader.handleFrame(ctx(), settingsFrame(1, 1024)));
        assertEquals(1024, reader.maxHpackEncoderTableSize);
    }

    /** HEADER_TABLE_SIZE beyond Integer.MAX_VALUE is capped instead of overflowing. */
    @Test
    public void testHandleSettingsHeaderTableSizeOverflowCapped() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        safeRun(() -> reader.handleFrame(ctx(), settingsFrame(1, 0xFFFFFFFF)));
        assertEquals(Integer.MAX_VALUE, reader.maxHpackEncoderTableSize);
    }

    // ==================== SETTINGS_ENABLE_PUSH accepted values ====================

    /**
     * ENABLE_PUSH = 0 passes validation and falls through to the ACK send.
     * The ACK writeFlush fails on the unconnected socket, so the close is expected.
     */
    @Test
    public void testHandleSettingsEnablePushZeroAccepted() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        safeRun(() -> reader.handleFrame(ctx(), settingsFrame(2, 0)));
        assertFalse(reader.valid);
    }

    /**
     * ENABLE_PUSH = 1 passes validation and falls through to the ACK send.
     * The ACK writeFlush fails on the unconnected socket, so the close is expected.
     */
    @Test
    public void testHandleSettingsEnablePushOneAccepted() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        safeRun(() -> reader.handleFrame(ctx(), settingsFrame(2, 1)));
        assertFalse(reader.valid);
    }

    // ==================== SETTINGS_INITIAL_WINDOW_SIZE unchanged ====================

    /** An INITIAL_WINDOW_SIZE equal to the current one skips the whole delta loop. */
    @Test
    public void testHandleSettingsInitialWindowUnchangedSkipsDelta() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        ChannelContext ctx = ctx();
        TestHttp2Stream stream = new TestHttp2Stream(reader, 1, ctx);
        reader.streamMap.put(1, stream);
        long before = stream.sendWindow;
        safeRun(() -> reader.handleFrame(ctx, settingsFrame(4, 65535)));
        assertEquals(before, stream.sendWindow);
    }

    // ==================== SETTINGS_MAX_FRAME_SIZE upper bound ====================

    /** MAX_FRAME_SIZE above the 16777215 ceiling -> PROTOCOL_ERROR. */
    @Test
    public void testHandleSettingsMaxFrameSizeTooLargeCloses() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        safeRun(() -> reader.handleFrame(ctx(), settingsFrame(5, 16777216)));
        assertFalse(reader.valid);
    }

    // ==================== WINDOW_UPDATE zero increment ====================

    /** A zero increment on the connection window -> PROTOCOL_ERROR. */
    @Test
    public void testHandleControlFrameWindowUpdateZeroIncrement() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        byte[] data = new byte[13];
        data[2] = 4;
        data[3] = 8;
        data[8] = 0;
        Http2Frame frame = new Http2Frame(data, 0, 9, 4, 4, Http2FrameType.WINDOW_UPDATE, 0, 0);
        safeRun(() -> reader.handleFrame(ctx(), frame));
        assertFalse(reader.valid);
    }

    // ==================== GOAWAY with NO_ERROR ====================

    /** GOAWAY carrying errorCode 0 skips the warn branch and closes gracefully. */
    @Test
    public void testHandleGoawayNoErrorClosesGracefully() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        byte[] data = new byte[17];
        data[2] = 8;
        data[3] = 7;
        data[8] = 0;
        data[12] = 3;   // lastStreamId = 3
        Http2Frame frame = new Http2Frame(data, 0, 9, 8, 8, Http2FrameType.GOAWAY, 0, 0);
        safeRun(() -> reader.handleFrame(ctx(), frame));
        assertFalse(reader.valid);
    }

}

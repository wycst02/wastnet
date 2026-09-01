package io.github.wycst.wastnet.http.integration;

import io.github.wycst.wastnet.http.HTTPServer;
import io.github.wycst.wastnet.http.handler.HttpRouterHandler;
import io.github.wycst.wastnet.http.upgrade.websocket.WebSocketConnection;
import io.github.wycst.wastnet.http.upgrade.websocket.WebSocketFrame;
import io.github.wycst.wastnet.http.upgrade.websocket.WebSocketResource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tests for WebSocket fragmentation and control frame interleaving (RFC 6455 §5.4).
 */
@org.junit.jupiter.api.condition.DisabledOnJre(org.junit.jupiter.api.condition.JRE.JAVA_8)
public class WebSocketFragmentationTest {

    private static HTTPServer server;
    private static int port;
    private static volatile CountDownLatch batchContinuationLatch;
    private static volatile CountDownLatch batchTextLatch;
    private static volatile AtomicReference<WebSocketFrame> batchContinuationFrame;
    private static volatile AtomicReference<String> batchTextMessage;

    @BeforeAll
    public static void startServer() {
        port = findFreePort();
        HttpRouterHandler router = new HttpRouterHandler();
        // STREAM endpoint for ping-between-continuation test
        router.ws("/ws-stream", new WebSocketResource().continuationStrategy(WebSocketResource.ContinuationStrategy.STREAM));
        // MERGE endpoint for orphan continuation test
        router.ws("/ws-merge", new WebSocketResource());
        // BATCH endpoint with small maxPayloadSize to force splitting
        WebSocketResource batchResource = new WebSocketResource() {
            @Override
            public void onMessage(WebSocketConnection conn, String msg) throws IOException {
                if (batchTextMessage != null) batchTextMessage.set(msg);
                if (batchTextLatch != null) batchTextLatch.countDown();
            }

            @Override
            public void onContinuation(WebSocketConnection conn, WebSocketFrame frame) throws IOException {
                if (batchContinuationFrame != null) batchContinuationFrame.set(frame);
                if (batchContinuationLatch != null) batchContinuationLatch.countDown();
            }
        };
        router.ws("/ws-batch", batchResource
                .continuationStrategy(WebSocketResource.ContinuationStrategy.BATCH)
                .maxPayloadSize(10));

        server = HTTPServer.of(port)
                .requestHandler(router)
                .startupBannerEnabled(false)
                .start();
    }

    @AfterAll
    public static void stopServer() {
        if (server != null) server.shutdown();
    }

    @Test
    public void testPingBetweenContinuationFrames() throws Exception {
        SocketChannel ch = connect("/ws-stream");

        // BINARY(fin=false) -> PING -> CONTINUATION(fin=true)
        sendFrame(ch, false, (byte) 0x2, "chunk1".getBytes());
        sendFrame(ch, true, (byte) 0x9, "ping".getBytes());
        sendFrame(ch, true, (byte) 0x0, "chunk2".getBytes());

        // Flush all frames
        ch.socket().getOutputStream().flush();

        // Read PONG response (server echoes PONG for PING)
        ByteBuffer buf = ByteBuffer.allocate(64);
        int n = readWithTimeout(ch, buf, 3000);
        Assertions.assertTrue(n > 0, "Should receive PONG response");
        buf.flip();
        byte firstByte = buf.get(0);
        // PONG frame: FIN=1, opcode=0xA => 0x80 | 0x0A = 0x8A
        Assertions.assertEquals((byte) 0x8A, firstByte, "Expected PONG frame (0x8A)");

        ch.close();
    }

    @Test
    public void testContinuationWithoutStartFrame() throws Exception {
        SocketChannel ch = connect("/ws-merge");

        // Orphan CONTINUATION(fin=true) — protocol error, server should close with 1002
        sendFrame(ch, true, (byte) 0x0, "orphan".getBytes());
        ch.socket().getOutputStream().flush();

        // Read close frame: 0x88 + len(2) + code(2)
        ByteBuffer buf = ByteBuffer.allocate(8);
        int n = readWithTimeout(ch, buf, 3000);
        Assertions.assertTrue(n >= 4, "Should receive close frame");

        buf.flip();
        int closeCode = (buf.get(2) & 0xFF) << 8 | (buf.get(3) & 0xFF);
        Assertions.assertEquals(1002, closeCode, "Close code should be 1002 (Protocol Error)");

        ch.close();
    }

    @Test
    public void testBatchStrategySplitsFragmentedMessage() throws Exception {
        batchTextLatch = new CountDownLatch(1);
        batchTextMessage = new AtomicReference<String>();
        batchContinuationLatch = new CountDownLatch(1);
        batchContinuationFrame = new AtomicReference<WebSocketFrame>();

        SocketChannel ch = connect("/ws-batch");

        // Three fragments: total 15 bytes, maxPayloadSize = 10, so BATCH splits after 10
        sendFrame(ch, false, (byte) 0x1, "AAAAA".getBytes());  // TEXT(fin=0)
        sendFrame(ch, false, (byte) 0x0, "BBBBB".getBytes());  // CONT(fin=0)
        sendFrame(ch, true,  (byte) 0x0, "CCCCC".getBytes());  // CONT(fin=1)
        ch.socket().getOutputStream().flush();

        // Verify onMessage receives the first batch (merged TEXT+CONT)
        Assertions.assertTrue(batchTextLatch.await(3, TimeUnit.SECONDS),
                "Should receive onMessage with first batch data");
        Assertions.assertEquals("AAAAABBBBB", batchTextMessage.get(),
                "onMessage should contain merged first two fragments");

        // Verify onContinuation receives the second batch
        Assertions.assertTrue(batchContinuationLatch.await(3, TimeUnit.SECONDS),
                "Should receive onContinuation with remaining data");
        Assertions.assertNotNull(batchContinuationFrame.get(),
                "Continuation frame should not be null");
        Assertions.assertEquals("CCCCC",
                new String(batchContinuationFrame.get().getData()),
                "Continuation data should be the third fragment");
        Assertions.assertTrue(batchContinuationFrame.get().isFin(),
                "Continuation frame should be marked fin=true as final batch");

        ch.close();
    }

    // ==================== Helpers ====================

    private SocketChannel connect(String path) throws IOException {
        SocketChannel ch = SocketChannel.open();
        ch.connect(new InetSocketAddress("127.0.0.1", port));
        ch.configureBlocking(false);
        handshake(ch, path);
        return ch;
    }

    private void handshake(SocketChannel ch, String path) throws IOException {
        String key = Base64.getEncoder().encodeToString("dGhlIHNhbXBsZSBub25jZQ==".getBytes());
        String req = "GET " + path + " HTTP/1.1\r\n"
                + "Host: localhost:" + port + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "\r\n";
        writeFully(ch, ByteBuffer.wrap(req.getBytes("US-ASCII")));

        ByteBuffer buf = ByteBuffer.allocate(4096);
        int n = readWithTimeout(ch, buf, 3000);
        Assertions.assertTrue(n > 0, "Should receive handshake response");
        buf.flip();
        String response = new String(buf.array(), 0, buf.limit(), "US-ASCII");
        Assertions.assertTrue(response.contains("101 Switching Protocols"),
                "Handshake should succeed: " + response.substring(0, Math.min(50, response.length())));
    }

    private void sendFrame(SocketChannel ch, boolean fin, byte opcode, byte[] payload) throws IOException {
        byte b0 = (byte) (opcode & 0x0F);
        if (fin) b0 |= 0x80;
        byte[] mask = {0x00, 0x00, 0x00, 0x00};

        int headerSize;
        if (payload.length < 126) headerSize = 2;
        else if (payload.length < 65536) headerSize = 4;
        else headerSize = 10;
        headerSize += 4;

        ByteBuffer frame = ByteBuffer.allocate(headerSize + payload.length);
        frame.put(b0);
        if (payload.length < 126) frame.put((byte) (payload.length | 0x80));
        else if (payload.length < 65536) { frame.put((byte) (126 | 0x80)); frame.putShort((short) payload.length); }
        else { frame.put((byte) (127 | 0x80)); frame.putLong(payload.length); }
        frame.put(mask);
        for (int i = 0; i < payload.length; ++i) frame.put((byte) (payload[i] ^ mask[i & 3]));
        frame.flip();
        writeFully(ch, frame);
    }

    private void writeFully(SocketChannel ch, ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) ch.write(buf);
    }

    private int readWithTimeout(SocketChannel ch, ByteBuffer buf, long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        int total = 0;
        while (System.currentTimeMillis() < deadline) {
            int n = ch.read(buf);
            if (n == -1) break; // EOF
            if (n > 0) total += n;
            if (total > 0) {
                // Got at least some data, give a bit more time for remaining
                deadline = Math.min(deadline, System.currentTimeMillis() + 500);
            }
            try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        return total;
    }

    private static int findFreePort() {
        try {
            ServerSocket ss = new ServerSocket(0);
            int p = ss.getLocalPort();
            ss.close();
            return p;
        } catch (Exception e) {
            return 18081;
        }
    }
}

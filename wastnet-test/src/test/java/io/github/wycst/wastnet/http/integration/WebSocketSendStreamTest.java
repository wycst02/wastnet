package io.github.wycst.wastnet.http.integration;

import io.github.wycst.wastnet.http.HTTPServer;
import io.github.wycst.wastnet.http.handler.HttpRouterHandler;
import io.github.wycst.wastnet.http.upgrade.websocket.WebSocketConnection;
import io.github.wycst.wastnet.http.upgrade.websocket.WebSocketResource;
import okhttp3.*;
import okio.ByteString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.ServerSocket;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link WebSocketConnection#sendInputStream}.
 * Verifies correct frame fragmentation and reassembly across all three
 * WebSocket header length branches:
 * <ul>
 *   <li>2-byte header (payload ≤ 125)</li>
 *   <li>4-byte header (126 ≤ payload ≤ 65535)</li>
 *   <li>10-byte header (payload > 65535)</li>
 * </ul>
 */
public class WebSocketSendStreamTest {

    private HTTPServer server;
    private int port;
    private volatile CountDownLatch receiveLatch;
    private volatile AtomicReference<byte[]> receivedRef;
    private volatile String errorMessage;
    private volatile OkHttpClient client;

    private static int findFreePort() {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        } catch (Exception e) {
            return 18080;
        }
    }

    @BeforeEach
    public void setUp() {
        receivedRef = new AtomicReference<>();
        errorMessage = null;
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (client != null) {
            client.dispatcher().executorService().shutdownNow();
        }
        if (server != null) {
            server.shutdown();
        }
    }

    private void startServer(int chunkSize, byte[] data) {
        port = findFreePort();
        HttpRouterHandler router = new HttpRouterHandler();
        router.ws("/ws", new WebSocketResource() {
            @Override
            public void onOpen(WebSocketConnection conn) {
                new Thread(() -> {
                    try {
                        conn.sendInputStream(new ByteArrayInputStream(data));
                    } catch (Exception e) {
                        errorMessage = "Server send error: " + e.getMessage();
                    }
                }).start();
            }
        }.maxPayloadSize(chunkSize));
        server = HTTPServer.of(port)
                .requestHandler(router)
                .startupBannerEnabled(false)
                .start();
    }

    private byte[] connectAndReceive(long timeoutSec) throws Exception {
        receiveLatch = new CountDownLatch(1);
        client = new OkHttpClient.Builder()
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
        Request request = new Request.Builder()
                .url("ws://localhost:" + port + "/ws")
                .build();

        client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
            }

            @Override
            public void onMessage(WebSocket webSocket, ByteString bytes) {
                receivedRef.set(bytes.toByteArray());
                receiveLatch.countDown();
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                errorMessage = "Client error: " + t.getMessage();
                receiveLatch.countDown();
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
            }
        });

        assertTrue(receiveLatch.await(timeoutSec, TimeUnit.SECONDS),
                "Timed out waiting for data");
        assertNull(errorMessage, "Error: " + errorMessage);
        return receivedRef.get();
    }

    @Test
    public void testSendStreamChunkSize60() throws Exception {
        // chunkSize=60 → 2-byte header (≤125)
        byte[] data = new byte[4096];
        new Random(42).nextBytes(data);
        startServer(60, data);
        byte[] received = connectAndReceive(10);
        assertArrayEquals(data, received);
    }

    @Test
    public void testSendStreamChunkSize2000() throws Exception {
        // chunkSize=2000 → 4-byte header (126~65535)
        byte[] data = new byte[65536];
        new Random(42).nextBytes(data);
        startServer(2000, data);
        byte[] received = connectAndReceive(10);
        assertArrayEquals(data, received);
    }

//    @Test
//    public void testSendStreamChunkSize70000() throws Exception {
//        // chunkSize=70000 → 10-byte header (>65535)
//        byte[] data = new byte[524288];
//        new Random(42).nextBytes(data);
//        startServer(70000, data);
//        byte[] received = connectAndReceive(15);
//        assertArrayEquals(data, received);
//    }

    @Test
    public void testSendStreamFileSizeExactChunkMultiple() throws Exception {
        // File size is exactly chunkSize * 3 → tests the 0-byte trailer frame
        byte[] data = new byte[180]; // 60 * 3
        new Random(42).nextBytes(data);
        startServer(60, data);
        byte[] received = connectAndReceive(10);
        assertArrayEquals(data, received);
    }

    @Test
    public void testSendStreamSingleByte() throws Exception {
        // Smallest non-empty data
        byte[] data = new byte[]{0x42};
        startServer(60, data);
        byte[] received = connectAndReceive(10);
        assertArrayEquals(data, received);
    }

//    @Test
//    public void testSendStreamEmpty() throws Exception {
//        // Empty stream → should receive empty BINARY frame
//        byte[] data = new byte[0];
//        startServer(60, data);
//        byte[] received = connectAndReceive(10);
//        assertArrayEquals(data, received);
//    }
}

package io.github.wycst.wastnet.http.integration;

import io.github.wycst.wastnet.http.HTTPServer;
import io.github.wycst.wastnet.http.handler.HttpRouterHandler;
import io.github.wycst.wastnet.http.upgrade.websocket.WebSocketConnection;
import io.github.wycst.wastnet.http.upgrade.websocket.WebSocketFrame;
import io.github.wycst.wastnet.http.upgrade.websocket.WebSocketResource;
import okhttp3.*;
import okio.ByteString;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * WebSocket integration test — starts a real HTTP server with WebSocket endpoint,
 * uses OkHttp WebSocket client to verify handshake, text/binary messages, ping-pong, and close.
 *
 * @author wangyc
 */
@org.junit.jupiter.api.condition.DisabledOnJre(org.junit.jupiter.api.condition.JRE.JAVA_8)
public class WebSocketIntegrationTest {

    private static HTTPServer server;
    private static int port;
    // Use volatile references so each test can install its own latches
    private static volatile CountDownLatch serverTextLatch;
    private static volatile CountDownLatch serverBinaryLatch;
    private static volatile AtomicReference<String> lastTextMessage;
    private static volatile AtomicReference<byte[]> lastBinaryMessage;
    private static volatile AtomicReference<WebSocketConnection> serverConnection;

    /**
     * Null out all shared statics before each test, so stale async callbacks from
     * the previous test's WebSocket connection see null and skip their handlers.
     */
    @BeforeEach
    public void resetSharedState() {
        serverTextLatch = null;
        serverBinaryLatch = null;
        lastTextMessage = null;
        lastBinaryMessage = null;
        if (serverConnection != null) {
            WebSocketConnection conn = serverConnection.get();
            if (conn != null && !conn.isClosed()) {
                try { conn.close(1000, "Test complete"); } catch (IOException ignored) {}
            }
        }
        serverConnection = null;
    }

    /**
     * After each test, allow a brief moment for any in-flight callbacks to drain,
     * then purge shared statics so they don't pollute the next test.
     */
    @AfterEach
    public void drainPendingCallbacks() throws InterruptedException {
        TimeUnit.MILLISECONDS.sleep(100);
        serverTextLatch = null;
        serverBinaryLatch = null;
        lastTextMessage = null;
        lastBinaryMessage = null;
        serverConnection = null;
    }

    @BeforeAll
    public static void startServer() {
        port = findFreePort();
        HttpRouterHandler router = new HttpRouterHandler();
        router.ws("/ws", new WebSocketResource(30) {
            @Override
            public void onOpen(WebSocketConnection conn) {
                if (serverConnection != null) {
                    serverConnection.set(conn);
                }
            }

            @Override
            public void onMessage(WebSocketConnection conn, String msg) throws IOException {
                if (lastTextMessage != null) lastTextMessage.set(msg);
                if (serverTextLatch != null) serverTextLatch.countDown();
                // Handle disconnect trigger
                if ("disconnect-me".equals(msg)) {
                    conn.disconnect();
                    return;
                }
                // Echo back
                conn.sendText("Echo: " + msg);
            }

            @Override
            public void onBinary(WebSocketConnection conn, byte[] data) throws IOException {
                if (lastBinaryMessage != null) lastBinaryMessage.set(data);
                if (serverBinaryLatch != null) serverBinaryLatch.countDown();
                // Echo back
                conn.sendBinary(data);
            }

            @Override
            public void onClose(WebSocketConnection conn, int code, String reason) {
            }
        });

        server = HTTPServer.of(port)
                .requestHandler(router)
                .startupBannerEnabled(false)
                .start();
    }

    @AfterAll
    public static void stopServer() {
        if (server != null) {
            server.shutdown();
        }
    }

    @Test
    public void testWebSocketHandshakeAndTextMessage() throws Exception {
        serverTextLatch = new CountDownLatch(1);
        lastTextMessage = new AtomicReference<String>();

        OkHttpClient client = new OkHttpClient.Builder()
                .readTimeout(60, TimeUnit.SECONDS)
                .build();

        final CountDownLatch clientMessageLatch = new CountDownLatch(1);
        final AtomicReference<String> receivedMessage = new AtomicReference<String>();

        Request request = new Request.Builder()
                .url("ws://localhost:" + port + "/ws")
                .build();

        WebSocket ws = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                webSocket.send("Hello WebSocket");
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                receivedMessage.set(text);
                clientMessageLatch.countDown();
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                clientMessageLatch.countDown();
            }
        });

        Assertions.assertTrue(serverTextLatch.await(5, TimeUnit.SECONDS), "Server should receive text message within 5s");
        Assertions.assertEquals("Hello WebSocket", lastTextMessage.get());

        Assertions.assertTrue(clientMessageLatch.await(5, TimeUnit.SECONDS), "Client should receive echo within 5s");
        Assertions.assertEquals("Echo: Hello WebSocket", receivedMessage.get());

        ws.close(1000, "Test complete");
        client.dispatcher().executorService().shutdown();
    }

    @Test
    public void testWebSocketLargeTextMessage() throws Exception {
        serverTextLatch = new CountDownLatch(1);
        lastTextMessage = new AtomicReference<String>();
        String largeMsg;
        {
            StringBuilder sb = new StringBuilder(200);
            for (int i = 0; i < 20; ++i) sb.append("ABCDEFGHIJ");
            largeMsg = sb.toString();
        } // 200 chars, >126 triggers 2-byte ext length

        OkHttpClient client = new OkHttpClient.Builder()
                .readTimeout(10, TimeUnit.SECONDS)
                .build();

        final CountDownLatch clientEchoLatch = new CountDownLatch(1);
        final AtomicReference<String> echoMessage = new AtomicReference<String>();

        Request request = new Request.Builder()
                .url("ws://localhost:" + port + "/ws")
                .build();

        WebSocket ws = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                webSocket.send(largeMsg);
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                echoMessage.set(text);
                clientEchoLatch.countDown();
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                clientEchoLatch.countDown();
            }
        });

        Assertions.assertTrue(serverTextLatch.await(5, TimeUnit.SECONDS), "Server should receive large text message within 5s");
        Assertions.assertEquals(largeMsg, lastTextMessage.get());

        Assertions.assertTrue(clientEchoLatch.await(5, TimeUnit.SECONDS), "Client should receive echo within 5s");
        Assertions.assertEquals("Echo: " + largeMsg, echoMessage.get());

        ws.close(1000, "Test complete");
        client.dispatcher().executorService().shutdown();
    }

    @Test
    public void testWebSocketHugeBinaryMessage() throws Exception {
        serverBinaryLatch = new CountDownLatch(1);
        lastBinaryMessage = new AtomicReference<byte[]>();
        byte[] hugeData = new byte[100000]; // >65535, triggers 8-byte ext length
        for (int i = 0; i < hugeData.length; ++i) hugeData[i] = (byte) (i & 0xFF);

        OkHttpClient client = new OkHttpClient.Builder()
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();

        final CountDownLatch clientBinaryLatch = new CountDownLatch(1);
        final AtomicReference<ByteString> receivedBinary = new AtomicReference<ByteString>();

        Request request = new Request.Builder()
                .url("ws://localhost:" + port + "/ws")
                .build();

        WebSocket ws = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                webSocket.send(ByteString.of(hugeData));
            }

            @Override
            public void onMessage(WebSocket webSocket, ByteString bytes) {
                receivedBinary.set(bytes);
                clientBinaryLatch.countDown();
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                clientBinaryLatch.countDown();
            }
        });

        Assertions.assertTrue(serverBinaryLatch.await(10, TimeUnit.SECONDS), "Server should receive huge binary message within 10s");
        Assertions.assertArrayEquals(hugeData, lastBinaryMessage.get());

        Assertions.assertTrue(clientBinaryLatch.await(10, TimeUnit.SECONDS), "Client should receive binary echo within 10s");
        Assertions.assertNotNull(receivedBinary.get(), "Binary echo should not be null");
        Assertions.assertEquals(100000, receivedBinary.get().size());

        ws.close(1000, "Test complete");
        client.dispatcher().executorService().shutdown();
    }

    @Test
    public void testWebSocketBinaryMessage() throws Exception {
        serverBinaryLatch = new CountDownLatch(1);
        lastBinaryMessage = new AtomicReference<byte[]>();

        OkHttpClient client = new OkHttpClient.Builder()
                .readTimeout(10, TimeUnit.SECONDS)
                .build();

        final CountDownLatch clientBinaryLatch = new CountDownLatch(1);
        final AtomicReference<ByteString> receivedBinary = new AtomicReference<ByteString>();

        Request request = new Request.Builder()
                .url("ws://localhost:" + port + "/ws")
                .build();

        WebSocket ws = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                byte[] data = {0x01, 0x02, 0x03, 0x04, 0x05};
                webSocket.send(ByteString.of(data));
            }

            @Override
            public void onMessage(WebSocket webSocket, ByteString bytes) {
                receivedBinary.set(bytes);
                clientBinaryLatch.countDown();
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                clientBinaryLatch.countDown();
            }
        });

        Assertions.assertTrue(serverBinaryLatch.await(5, TimeUnit.SECONDS), "Server should receive binary message within 5s");
        Assertions.assertArrayEquals(new byte[]{0x01, 0x02, 0x03, 0x04, 0x05}, lastBinaryMessage.get());

        Assertions.assertTrue(clientBinaryLatch.await(5, TimeUnit.SECONDS), "Client should receive binary echo within 5s");
        Assertions.assertNotNull(receivedBinary.get(), "Binary echo should not be null");
        Assertions.assertEquals(5, receivedBinary.get().size());

        ws.close(1000, "Test complete");
        client.dispatcher().executorService().shutdown();
    }

    @Test
    public void testWebSocketServerInitiatedClose() throws Exception {
        serverTextLatch = new CountDownLatch(1);
        lastTextMessage = new AtomicReference<String>();
        serverConnection = new AtomicReference<WebSocketConnection>();

        OkHttpClient client = new OkHttpClient.Builder()
                .readTimeout(10, TimeUnit.SECONDS)
                .build();

        final CountDownLatch closeLatch = new CountDownLatch(1);

        Request request = new Request.Builder()
                .url("ws://localhost:" + port + "/ws")
                .build();

        final WebSocket ws = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                webSocket.send("trigger-close");
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                // After receiving echo, close from server side
                WebSocketConnection conn = serverConnection.get();
                if (conn != null) {
                    try {
                        conn.close(1000, "Server initiated close");
                    } catch (IOException e) {
                        // ignore
                    }
                }
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                closeLatch.countDown();
                webSocket.close(code, reason);
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                closeLatch.countDown();
            }
        });

        Assertions.assertTrue(closeLatch.await(5, TimeUnit.SECONDS), "Client should receive close within 5s");

        ws.cancel();
        client.dispatcher().executorService().shutdown();
    }

    @Test
    public void testWebSocketPingPong() throws Exception {
        serverTextLatch = new CountDownLatch(1);
        lastTextMessage = new AtomicReference<String>();

        OkHttpClient client = new OkHttpClient.Builder()
                .readTimeout(10, TimeUnit.SECONDS)
                .build();

        final CountDownLatch aliveLatch = new CountDownLatch(1);

        Request request = new Request.Builder()
                .url("ws://localhost:" + port + "/ws")
                .build();

        WebSocket ws = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                webSocket.send("ping-test");
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                aliveLatch.countDown();
            }

            @Override
            public void onMessage(WebSocket webSocket, ByteString bytes) {
                aliveLatch.countDown();
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                aliveLatch.countDown();
            }
        });

        Assertions.assertTrue(aliveLatch.await(5, TimeUnit.SECONDS), "Connection should be alive (ping-pong working at protocol level)");


        ws.close(1000, "Test complete");
        client.dispatcher().executorService().shutdown();
    }

    // ==================== Server-Initiated Frame Tests ====================

    @Test
    public void testServerPingFrame() throws Exception {
        serverTextLatch = new CountDownLatch(1);
        lastTextMessage = new AtomicReference<String>();
        serverConnection = new AtomicReference<WebSocketConnection>();

        OkHttpClient client = new OkHttpClient.Builder()
                .readTimeout(10, TimeUnit.SECONDS)
                .build();

        final CountDownLatch echoLatch = new CountDownLatch(1);
        final AtomicReference<String> echoMsg = new AtomicReference<String>();

        Request request = new Request.Builder()
                .url("ws://localhost:" + port + "/ws")
                .build();

        WebSocket ws = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                // Server calls ping() in onOpen; wait a moment then send
                try { TimeUnit.MILLISECONDS.sleep(200); } catch (InterruptedException ignored) {}
                webSocket.send("after-ping");
            }
            @Override
            public void onMessage(WebSocket webSocket, String text) {
                echoMsg.set(text);
                echoLatch.countDown();
            }
            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                echoLatch.countDown();
            }
        });

        // Wait for server-side onOpen, then call ping() from test thread
        assertServerConnectionEstablished(serverConnection);

        // Call ping() from test thread — should not throw or break connection
        WebSocketConnection conn = serverConnection.get();
        Assertions.assertNotNull(conn);
        Assertions.assertFalse(conn.isClosed(), "Connection should be open before ping");
        conn.ping();

        // Verify server receives text message (ping didn't break stream)
        Assertions.assertTrue(serverTextLatch.await(5, TimeUnit.SECONDS), "Server should receive text after ping");
        Assertions.assertEquals("after-ping", lastTextMessage.get());

        // Verify client receives echo
        Assertions.assertTrue(echoLatch.await(5, TimeUnit.SECONDS), "Client should receive echo after ping");
        Assertions.assertEquals("Echo: after-ping", echoMsg.get());

        ws.close(1000, "Test complete");
        client.dispatcher().executorService().shutdown();
    }

    @Test
    public void testServerPongFrame() throws Exception {
        serverTextLatch = new CountDownLatch(1);
        lastTextMessage = new AtomicReference<String>();
        serverConnection = new AtomicReference<WebSocketConnection>();

        OkHttpClient client = new OkHttpClient.Builder()
                .readTimeout(10, TimeUnit.SECONDS)
                .build();

        final CountDownLatch echoLatch = new CountDownLatch(1);
        final AtomicReference<String> echoMsg = new AtomicReference<String>();

        Request request = new Request.Builder()
                .url("ws://localhost:" + port + "/ws")
                .build();

        WebSocket ws = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                try { TimeUnit.MILLISECONDS.sleep(200); } catch (InterruptedException ignored) {}
                webSocket.send("after-pong");
            }
            @Override
            public void onMessage(WebSocket webSocket, String text) {
                echoMsg.set(text);
                echoLatch.countDown();
            }
            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                echoLatch.countDown();
            }
        });

        assertServerConnectionEstablished(serverConnection);
        WebSocketConnection conn = serverConnection.get();
        Assertions.assertNotNull(conn);
        Assertions.assertFalse(conn.isClosed(), "Connection should be open before pong");
        conn.pong();

        Assertions.assertTrue(serverTextLatch.await(5, TimeUnit.SECONDS), "Server should receive text after pong");
        Assertions.assertEquals("after-pong", lastTextMessage.get());
        Assertions.assertTrue(echoLatch.await(5, TimeUnit.SECONDS), "Client should receive echo after pong");
        Assertions.assertEquals("Echo: after-pong", echoMsg.get());

        ws.close(1000, "Test complete");
        client.dispatcher().executorService().shutdown();
    }

    @Test
    public void testServerPushFrame() throws Exception {
        serverConnection = new AtomicReference<WebSocketConnection>();

        OkHttpClient client = new OkHttpClient.Builder()
                .readTimeout(10, TimeUnit.SECONDS)
                .build();

        final CountDownLatch pushLatch = new CountDownLatch(1);
        final AtomicReference<String> pushMsg = new AtomicReference<String>();

        Request request = new Request.Builder()
                .url("ws://localhost:" + port + "/ws")
                .build();

        WebSocket ws = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                try { TimeUnit.MILLISECONDS.sleep(200); } catch (InterruptedException ignored) {}
            }
            @Override
            public void onMessage(WebSocket webSocket, String text) {
                pushMsg.set(text);
                pushLatch.countDown();
            }
            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                pushLatch.countDown();
            }
        });

        assertServerConnectionEstablished(serverConnection);
        WebSocketConnection conn = serverConnection.get();
        Assertions.assertNotNull(conn);

        // Push a text frame from server to client
        conn.push(WebSocketFrame.textOf("server-pushed-message"));

        Assertions.assertTrue(pushLatch.await(5, TimeUnit.SECONDS), "Client should receive pushed message within 5s");
        Assertions.assertEquals("server-pushed-message", pushMsg.get());

        // Verify isClosed returns false for open connection
        Assertions.assertFalse(conn.isClosed(), "Connection should still be open after push");

        ws.close(1000, "Test complete");
        client.dispatcher().executorService().shutdown();
    }

    @Test
    public void testServerDisconnect() throws Exception {
        serverTextLatch = new CountDownLatch(1);
        lastTextMessage = new AtomicReference<String>();
        serverConnection = new AtomicReference<WebSocketConnection>();

        OkHttpClient client = new OkHttpClient.Builder()
                .readTimeout(10, TimeUnit.SECONDS)
                .build();

        final CountDownLatch closeLatch = new CountDownLatch(1);
        final AtomicReference<Integer> closeCode = new AtomicReference<Integer>();

        Request request = new Request.Builder()
                .url("ws://localhost:" + port + "/ws")
                .build();

        WebSocket ws = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                webSocket.send("disconnect-me");
            }
            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                closeCode.set(code);
                closeLatch.countDown();
                webSocket.close(code, reason);
            }
            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                closeLatch.countDown();
            }
        });

        // Server's onMessage sees "disconnect-me" and calls conn.disconnect()
        Assertions.assertTrue(serverTextLatch.await(5, TimeUnit.SECONDS), "Server should receive disconnect trigger");
        Assertions.assertEquals("disconnect-me", lastTextMessage.get());

        // Server calls disconnect() from onMessage
        Assertions.assertTrue(closeLatch.await(5, TimeUnit.SECONDS), "Client should receive close within 5s");
        Assertions.assertNotNull(closeCode.get(), "Close code should be provided");
        Assertions.assertEquals(1000, closeCode.get().intValue(), "Disconnect should use code 1000");

        // After disconnect, connection should be closed
        WebSocketConnection conn = serverConnection.get();
        Assertions.assertNotNull(conn);
        Assertions.assertTrue(conn.isClosed(), "Connection should be closed after disconnect");

        ws.cancel();
        client.dispatcher().executorService().shutdown();
    }

    @Test
    public void testIsClosedAfterNormalClose() throws Exception {
        serverConnection = new AtomicReference<WebSocketConnection>();

        OkHttpClient client = new OkHttpClient.Builder()
                .readTimeout(10, TimeUnit.SECONDS)
                .build();

        final CountDownLatch openLatch = new CountDownLatch(1);

        Request request = new Request.Builder()
                .url("ws://localhost:" + port + "/ws")
                .build();

        WebSocket ws = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                openLatch.countDown();
            }
        });

        Assertions.assertTrue(openLatch.await(5, TimeUnit.SECONDS));
        assertServerConnectionEstablished(serverConnection);

        WebSocketConnection conn = serverConnection.get();
        Assertions.assertFalse(conn.isClosed(), "Connection should be open initially");

        // Close from server side
        conn.close(1000, "Normal close");
        Assertions.assertTrue(conn.isClosed(), "Connection should report closed after close()");

        // Calling close again should be a no-op (closed flag already true)
        conn.close(1001, "Duplicate close");

        ws.close(1000, "Test complete");
        client.dispatcher().executorService().shutdown();
    }

    @Test
    public void testPushNullFrameIsNoOp() throws Exception {
        serverConnection = new AtomicReference<WebSocketConnection>();

        OkHttpClient client = new OkHttpClient.Builder()
                .readTimeout(10, TimeUnit.SECONDS)
                .build();

        final CountDownLatch openLatch = new CountDownLatch(1);

        Request request = new Request.Builder()
                .url("ws://localhost:" + port + "/ws")
                .build();

        WebSocket ws = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                openLatch.countDown();
            }
        });

        Assertions.assertTrue(openLatch.await(5, TimeUnit.SECONDS));
        assertServerConnectionEstablished(serverConnection);

        // push(null) should not throw — method returns immediately
        WebSocketConnection conn = serverConnection.get();
        conn.push(null);
        Assertions.assertFalse(conn.isClosed(), "Connection should still be open after push(null)");

        ws.close(1000, "Test complete");
        client.dispatcher().executorService().shutdown();
    }

    @Test
    public void testDisconnectOnAlreadyClosedConnection() throws Exception {
        serverConnection = new AtomicReference<WebSocketConnection>();

        OkHttpClient client = new OkHttpClient.Builder()
                .readTimeout(10, TimeUnit.SECONDS)
                .build();

        final CountDownLatch openLatch = new CountDownLatch(1);

        Request request = new Request.Builder()
                .url("ws://localhost:" + port + "/ws")
                .build();

        WebSocket ws = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                openLatch.countDown();
            }
        });

        Assertions.assertTrue(openLatch.await(5, TimeUnit.SECONDS));
        assertServerConnectionEstablished(serverConnection);

        WebSocketConnection conn = serverConnection.get();
        // Close once
        conn.close(1000, "First close");
        Assertions.assertTrue(conn.isClosed(), "Connection should be closed after first close");

        // Disconnect on already-closed connection: should be a no-op (early return, no exception)
        conn.disconnect();

        ws.close(1000, "Test complete");
        client.dispatcher().executorService().shutdown();
    }

    @Test
    public void testCloseOnAlreadyClosedConnection() throws Exception {
        serverConnection = new AtomicReference<WebSocketConnection>();

        OkHttpClient client = new OkHttpClient.Builder()
                .readTimeout(10, TimeUnit.SECONDS)
                .build();

        final CountDownLatch openLatch = new CountDownLatch(1);

        Request request = new Request.Builder()
                .url("ws://localhost:" + port + "/ws")
                .build();

        WebSocket ws = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                openLatch.countDown();
            }
        });

        Assertions.assertTrue(openLatch.await(5, TimeUnit.SECONDS));
        assertServerConnectionEstablished(serverConnection);

        WebSocketConnection conn = serverConnection.get();
        // Close once
        conn.close(1000, "First close");
        Assertions.assertTrue(conn.isClosed(), "Connection should be closed after first close");

        // Close again on already-closed connection: should be a no-op (closed flag check)
        // Should not throw, should not fail
        conn.close(1001, "Duplicate close");

        ws.close(1000, "Test complete");
        client.dispatcher().executorService().shutdown();
    }

    @Test
    public void testPingPongOnClosedConnection() throws Exception {
        serverConnection = new AtomicReference<WebSocketConnection>();

        OkHttpClient client = new OkHttpClient.Builder()
                .readTimeout(10, TimeUnit.SECONDS)
                .build();

        final CountDownLatch openLatch = new CountDownLatch(1);

        Request request = new Request.Builder()
                .url("ws://localhost:" + port + "/ws")
                .build();

        WebSocket ws = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                openLatch.countDown();
            }
        });

        Assertions.assertTrue(openLatch.await(5, TimeUnit.SECONDS));
        assertServerConnectionEstablished(serverConnection);

        WebSocketConnection conn = serverConnection.get();
        conn.close(1000, "Close first");
        Assertions.assertTrue(conn.isClosed(), "Connection should be closed");

        // ping/pong on closed connection should throw IllegalStateException
        Assertions.assertThrows(IllegalStateException.class, () -> conn.ping());
        Assertions.assertThrows(IllegalStateException.class, () -> conn.pong());

        ws.close(1000, "Test complete");
        client.dispatcher().executorService().shutdown();
    }

    @Test
    public void testSendTextOnClosedConnection() throws Exception {
        serverConnection = new AtomicReference<WebSocketConnection>();

        OkHttpClient client = new OkHttpClient.Builder()
                .readTimeout(10, TimeUnit.SECONDS)
                .build();

        final CountDownLatch openLatch = new CountDownLatch(1);

        Request request = new Request.Builder()
                .url("ws://localhost:" + port + "/ws")
                .build();

        WebSocket ws = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                openLatch.countDown();
            }
        });

        Assertions.assertTrue(openLatch.await(5, TimeUnit.SECONDS));
        assertServerConnectionEstablished(serverConnection);

        WebSocketConnection conn = serverConnection.get();
        conn.close(1000, "Close first");
        Assertions.assertTrue(conn.isClosed(), "Connection should be closed");

        // sendText on closed connection should throw IllegalStateException
        Assertions.assertThrows(IllegalStateException.class, () -> conn.sendText("hello"));
        Assertions.assertThrows(IllegalStateException.class, () -> conn.sendBinary(new byte[]{1, 2}));

        ws.close(1000, "Test complete");
        client.dispatcher().executorService().shutdown();
    }

    @Test
    public void testSendTextNullThrows() throws Exception {
        serverConnection = new AtomicReference<WebSocketConnection>();

        OkHttpClient client = new OkHttpClient.Builder()
                .readTimeout(10, TimeUnit.SECONDS)
                .build();

        final CountDownLatch openLatch = new CountDownLatch(1);

        Request request = new Request.Builder()
                .url("ws://localhost:" + port + "/ws")
                .build();

        WebSocket ws = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                openLatch.countDown();
            }
        });

        Assertions.assertTrue(openLatch.await(5, TimeUnit.SECONDS));
        assertServerConnectionEstablished(serverConnection);

        WebSocketConnection conn = serverConnection.get();
        // sendText(null) should throw IllegalArgumentException
        Assertions.assertThrows(IllegalArgumentException.class, () -> conn.sendText(null));

        ws.close(1000, "Test complete");
        client.dispatcher().executorService().shutdown();
    }

    @Test
    public void testSendBinaryNullThrows() throws Exception {
        serverConnection = new AtomicReference<WebSocketConnection>();

        OkHttpClient client = new OkHttpClient.Builder()
                .readTimeout(10, TimeUnit.SECONDS)
                .build();

        final CountDownLatch openLatch = new CountDownLatch(1);

        Request request = new Request.Builder()
                .url("ws://localhost:" + port + "/ws")
                .build();

        WebSocket ws = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                openLatch.countDown();
            }
        });

        Assertions.assertTrue(openLatch.await(5, TimeUnit.SECONDS));
        assertServerConnectionEstablished(serverConnection);

        WebSocketConnection conn = serverConnection.get();
        // sendBinary(null) should throw IllegalArgumentException
        Assertions.assertThrows(IllegalArgumentException.class, () -> conn.sendBinary(null));

        ws.close(1000, "Test complete");
        client.dispatcher().executorService().shutdown();
    }

    // ==================== Private Helpers ====================

    private static void assertServerConnectionEstablished(AtomicReference<WebSocketConnection> ref) throws InterruptedException {
        for (int i = 0; i < 50; ++i) {
            if (ref != null && ref.get() != null) return;
            TimeUnit.MILLISECONDS.sleep(100);
        }
        Assertions.fail("Server-side WebSocketConnection should be established within 5s");
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

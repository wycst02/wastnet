package io.github.wycst.wastnet.http.handler;

import io.github.wycst.wastnet.http.HTTPServer;
import io.github.wycst.wastnet.http.HttpStatus;
import org.junit.jupiter.api.*;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Integration tests for HttpServerChannelHandler using real server + raw socket connections.
 * <p>
 * Covers error handling paths: handleBadRequest, handleExceptionHandlerFailure, handleInternalException.
 */
@org.junit.jupiter.api.condition.DisabledOnJre(org.junit.jupiter.api.condition.JRE.JAVA_8)
public class HttpServerChannelHandlerIntegrationTest {

    private static HTTPServer serverWithExceptionHandler;
    private static HTTPServer serverWithoutExceptionHandler;
    private static int port1;
    private static int port2;

    @BeforeAll
    public static void startServers() throws Exception {
        // Server 1: has exception handler that throws, printStackTraceError=true
        port1 = findFreePort();
        serverWithExceptionHandler = HTTPServer.of(port1)
                .requestHandler((request, response) -> {
                    throw new RuntimeException("Application error");
                })
                .exceptionHandler((request, response, throwable) -> {
                    throw new RuntimeException("Exception handler failure");
                })
                .startupBannerEnabled(false)
                .start();

        // Server 2: no exception handler (covers handleInternalException directly)
        port2 = findFreePort();
        serverWithoutExceptionHandler = HTTPServer.of(port2)
                .requestHandler((request, response) -> {
                    throw new RuntimeException("App error no handler");
                })
                .startupBannerEnabled(false)
                .start();
    }

    @AfterAll
    public static void stopServers() throws Exception {
        if (serverWithExceptionHandler != null) serverWithExceptionHandler.shutdown();
        if (serverWithoutExceptionHandler != null) serverWithoutExceptionHandler.shutdown();
    }

    // ==================== handleApplicationException + handleExceptionHandlerFailure + handleInternalException ====================

    @Test
    public void testExceptionHandlerItselfThrows() throws Exception {
        // Request → handler throws → exceptionHandler throws →
        // handleExceptionHandlerFailure → handleInternalException (printStackTraceError=true) → 500
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", port1), 5000);
            socket.setSoTimeout(30000);
            OutputStream out = socket.getOutputStream();
            out.write("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes("US-ASCII"));
            out.flush();

            byte[] buf = new byte[4096];
            int n = socket.getInputStream().read(buf);
            Assertions.assertTrue(n > 0, "Should get a response");
            String response = new String(buf, 0, n, "US-ASCII");
            Assertions.assertTrue(response.contains("500"),
                    "Expected 500, got: " + response.substring(0, Math.min(60, response.length())));
        } finally {
            socket.close();
        }
    }

    // ==================== handleApplicationException without exceptionHandler ====================

    @Test
    public void testAppExceptionWithoutExceptionHandler() throws Exception {
        // Request → handler throws → exceptionHandler=null → handleInternalException → 500
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", port2), 5000);
            socket.setSoTimeout(30000);
            OutputStream out = socket.getOutputStream();
            out.write("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes("US-ASCII"));
            out.flush();

            byte[] buf = new byte[4096];
            int n = socket.getInputStream().read(buf);
            Assertions.assertTrue(n > 0, "Should get a response");
            String response = new String(buf, 0, n, "US-ASCII");
            // handleInternalException with printStackTraceError=true → log.error + 500
            Assertions.assertTrue(response.contains("500"),
                    "Expected 500, got: " + response.substring(0, Math.min(60, response.length())));
        } finally {
            socket.close();
        }
    }

    // ==================== handleBadRequest via malformed HTTP ====================

    @Test
    public void testBadRequestNegativeContentLength() throws Exception {
        // Negative Content-Length → BadHttpRequest → handleBadRequest
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", port1), 5000);
            socket.setSoTimeout(30000);
            OutputStream out = socket.getOutputStream();
            out.write("POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: -1\r\n\r\n".getBytes("US-ASCII"));
            out.flush();

            byte[] buf = new byte[4096];
            int n = socket.getInputStream().read(buf);
            Assertions.assertTrue(n > 0, "Should get error response");
            String response = new String(buf, 0, n, "US-ASCII");
            Assertions.assertTrue(response.contains("400"),
                    "Expected 400, got: " + response.substring(0, Math.min(60, response.length())));
        } finally {
            socket.close();
        }
    }

    @Test
    public void testBadRequestUriTooLong() throws Exception {
        // URI exceeding max length → BadHttpRequest → handleBadRequest
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", port1), 5000);
            socket.setSoTimeout(30000);
            OutputStream out = socket.getOutputStream();
            StringBuilder sb = new StringBuilder("GET /");
            for (int i = 0; i < 10240; ++i) sb.append('a');
            sb.append(" HTTP/1.1\r\nHost: localhost\r\n\r\n");
            out.write(sb.toString().getBytes("US-ASCII"));
            out.flush();

            byte[] buf = new byte[4096];
            int n = socket.getInputStream().read(buf);
            Assertions.assertTrue(n > 0, "Should get error response");
            // handleBadRequest is called (just verify we get a response, not a timeout)
        } finally {
            socket.close();
        }
    }

    @Test
    public void testBadRequestDoubleCr() throws Exception {
        // Double \\r after header boundary → BAD_REQUEST → handleBadRequest
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", port1), 5000);
            socket.setSoTimeout(30000);
            OutputStream out = socket.getOutputStream();
            out.write("GET / HTTP/1.1\r\nHost: localhost\r\n\r\r\n".getBytes("US-ASCII"));
            out.flush();

            byte[] buf = new byte[4096];
            int n = socket.getInputStream().read(buf);
            Assertions.assertTrue(n > 0, "Should get error response");
            String response = new String(buf, 0, n, "US-ASCII");
            Assertions.assertTrue(response.contains("400"),
                    "Expected 400, got: " + response.substring(0, Math.min(60, response.length())));
        } finally {
            socket.close();
        }
    }

    // ==================== Normal request (baseline) ====================

    @Test
    public void testNormalRequestOnServer2() throws Exception {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", port2), 5000);
            socket.setSoTimeout(30000);
            OutputStream out = socket.getOutputStream();
            out.write("GET /some-path HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes("US-ASCII"));
            out.flush();

            byte[] buf = new byte[4096];
            int n = socket.getInputStream().read(buf);
            Assertions.assertTrue(n > 0);
            String response = new String(buf, 0, n, "US-ASCII");
            // The handler always throws, so we should get 500
            Assertions.assertTrue(response.contains("500"),
                    "Expected 500, got: " + response.substring(0, Math.min(60, response.length())));
        } finally {
            socket.close();
        }
    }

    private static int findFreePort() {
        try {
            ServerSocket ss = new ServerSocket(0);
            int p = ss.getLocalPort();
            ss.close();
            return p;
        } catch (Exception e) {
            return 18080;
        }
    }
}

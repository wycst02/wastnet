package io.github.wycst.wastnet.examples.h2;

import io.github.wycst.wast.log.Log;
import io.github.wycst.wast.log.LogFactory;
import io.github.wycst.wastnet.http.HTTPServer;
import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.http.HttpStatus;
import io.github.wycst.wastnet.http.handler.HttpRoute;
import io.github.wycst.wastnet.http.handler.HttpRouterHandler;
import io.github.wycst.wastnet.http.upgrade.websocket.WebSocketConnection;
import io.github.wycst.wastnet.http.upgrade.websocket.WebSocketResource;
import io.github.wycst.wastnet.socket.tcp.NioConfig;

import java.io.IOException;

/**
 * HTTP/2 over TCP (h2c) test.
 * <p>
 * Test h2c upgrade by using nghttp or similar tools:
 * <pre>
 * nghttp -v -d /path/to/file http://localhost:8080/h2c-upload
 * </pre>
 *
 * @author wangyc
 */
public class H2CTest {

    private static Log log = LogFactory.getLog(H2CTest.class);

    public static void main(String[] args) throws Exception {
        NioConfig nioConfig = new NioConfig();
        nioConfig.testMode();
        nioConfig.setWorkerNum(4);

        // Use HttpRouterHandler with contextPath
        HttpRouterHandler router = new HttpRouterHandler("/app");

        // Register h2c endpoint
        router.h2c("/h2c");

        // Also support WebSocket on the same router
        router.ws("/ws", new WebSocketResource(30) {
            @Override
            public void onOpen(WebSocketConnection connection) {
                log.info("WebSocket opened: {}", connection.id());
            }

            @Override
            public void onMessage(WebSocketConnection connection, String message) {
                log.info("WebSocket message: {}", message);
                try {
                    connection.sendText("Echo: " + message);
                } catch (IOException e) {
                    log.error("Failed to send message", e);
                }
            }

            @Override
            public void onClose(WebSocketConnection connection, int code, String reason) {
                log.info("WebSocket closed: {} - {}", code, reason);
            }
        });

        // Regular HTTP routes
        router.route("/api/test", new HttpRoute() {
            @Override
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.OK)
                        .contentType("application/json")
                        .body("{\"status\":\"ok\"}".getBytes());
            }
        });

        HTTPServer server = HTTPServer.of(8080, nioConfig)
                .applicationProtocols(new String[]{"h2c"})
                .requestHandler(router)
                .start();

        log.info("H2C test server started on http://localhost:{}/app", server.getPort());
        log.info("");
        log.info("Test endpoints:");
        log.info("  HTTP:     http://localhost:{}/app/api/test", server.getPort());
        log.info("  WebSocket: ws://localhost:{}/app/ws", server.getPort());
        log.info("  h2c:      http://localhost:{}/app/h2c", server.getPort());
        log.info("");
        log.info("Test with nghttp for h2c:");
        log.info("  nghttp -v http://localhost:{}/app/h2c", server.getPort());
    }
}

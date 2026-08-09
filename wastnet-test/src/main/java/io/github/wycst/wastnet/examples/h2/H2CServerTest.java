package io.github.wycst.wastnet.examples.h2;

import io.github.wycst.wastnet.http.HTTPServer;
import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.http.HttpStatus;
import io.github.wycst.wastnet.http.handler.HttpRoute;
import io.github.wycst.wastnet.http.handler.HttpRouterHandler;

/**
 * h2c (HTTP/2 Cleartext Upgrade) server for testing.
 * <p>
 * Supports both h2c Upgrade (HTTP/1.1 → HTTP/2) and prior knowledge modes.
 * <p>
 * Test with:
 * <pre>
 *   # h2c prior knowledge (direct HTTP/2)
 *   curl --http2-prior-knowledge http://localhost:8080/app/api/test
 *
 *   # h2c Upgrade (HTTP/1.1 → upgrade → HTTP/2)
 *   curl --http2 http://localhost:8080/app/api/test
 *
 *   # nghttp2 client
 *   nghttp -v http://localhost:8080/app/h2c
 *
 *   # Raw NIO test client (in wastnet-test project)
 *   # io.github.wycst.test.H2CUpgradeTest
 * </pre>
 *
 * @author wangyc
 */
public class H2CServerTest {

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;

        HttpRouterHandler router = new HttpRouterHandler("/app");

        // Register H2C endpoint (required for h2c upgrade handshake)
        router.h2c("/h2c");

        // Regular HTTP route for testing
        router.route("/api/test", new HttpRoute() {
            @Override
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.OK)
                        .contentType("application/json;charset=utf-8")
                        .body("{\"status\":\"ok\",\"protocol\":\"" + request.getHttpVersion() + "\"}".getBytes());
            }
        });

        HTTPServer.of(port)
                .applicationProtocols(new String[]{"h2c", "http/1.1"})
                .requestHandler(router)
                .start();

        System.out.println("h2c test server started:");
        System.out.println("  HTTP:     http://localhost:" + port + "/app/api/test");
        System.out.println("  h2c:      http://localhost:" + port + "/app/h2c");
        System.out.println();
        System.out.println("Test commands:");
        System.out.println("  curl --http2-prior-knowledge http://localhost:" + port + "/app/api/test");
        System.out.println("  curl --http2 http://localhost:" + port + "/app/api/test");
        System.out.println("  nghttp -v http://localhost:" + port + "/app/h2c");
    }
}

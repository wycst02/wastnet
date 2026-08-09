package io.github.wycst.wastnet.examples.http;

import io.github.wycst.wastnet.http.HTTPServer;
import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.http.handler.HttpRoute;
import io.github.wycst.wastnet.http.handler.HttpRouterHandler;

/**
 * Test HTTP server with localOnly mode (bound to 127.0.0.1)
 */
public class LocalhostServerTest {

    public static void main(String[] args) {
        int port = Integer.getInteger("port", 8080);

        HttpRouterHandler router = new HttpRouterHandler("/");
        router.route("/", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.setHeader("Content-Type", "text/plain; charset=utf-8");
                response.body("Hello from server!");
            }
        });

        HTTPServer.of(port).localOnly(true).requestHandler(router).start();

        System.out.println("HTTP server started on http://127.0.0.1:" + port + "/");
        System.out.println("  Only local connections are accepted.");
        System.out.println("  Test: curl http://127.0.0.1:" + port + "/");
    }
}

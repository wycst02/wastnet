package io.github.wycst.wastnet.examples.http;

import io.github.wycst.wastnet.http.HTTPServer;
import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.http.handler.HttpRequestHandler;
import io.github.wycst.wastnet.http.handler.HttpResourceRoute;
import io.github.wycst.wastnet.http.handler.HttpRoute;
import io.github.wycst.wastnet.http.handler.HttpRouterHandler;
import io.github.wycst.wastnet.http.proxy.HttpProxyConfig;
import io.github.wycst.wastnet.http.proxy.HttpProxyRoute;
import io.github.wycst.wastnet.http.upgrade.websocket.WebSocketConnection;
import io.github.wycst.wastnet.http.upgrade.websocket.WebSocketResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Test for HttpRouterHandler and HttpResourceRoute
 */
public class MiniNginxHttp2 {

    public static void main(String[] args) throws Exception {
        // Configurable via system properties: -Dport=8080 -DcontextPath=/schedule-layout-ui -DdocBase=E:/2026_ws/failure-schedule/front-end/dist
        // Enable gzip compression
        System.setProperty("wastnet.http.gzip", "true");

        int port = Integer.getInteger("port", 8000);
        String contextPath = System.getProperty("contextPath", "/screen-layout/schedule/fault-command-dispatch-web");
//        String contextPath = System.getProperty("contextPath", "/");
        String docBase = System.getProperty("docBase", "E:/2026_ws/tianti/biz-fault-command-dispatch-web/dist");
        String gatewayTarget = System.getProperty("gateway", "http://192.168.1.226:19028");

        HttpRoute userHandler = new HttpRoute() {
            @Override
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(200).write(("User path: " + path).getBytes());
            }
        };

        HttpRoute apiHandler = new HttpRoute() {
            @Override
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(200).write("OK".getBytes());
            }
        };

        HttpRoute regexHandler = new HttpRoute() {
            @Override
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(200).write(("Regex path: " + path).getBytes());
            }
        };

        final HttpRouterHandler router = new HttpRouterHandler(contextPath);

        // Exact match
        router.exactRoute("/user", userHandler);

        // Prefix match
        router.route("/api", apiHandler);

        // Regex match
        router.route("^/v\\d+/resource$", regexHandler);

        // Proxy route
        HttpProxyConfig proxyConfig = HttpProxyConfig.target(gatewayTarget)
                .upgrade(true)
                .readTimeout(5000)
                .rewrite(new HttpProxyConfig.RewriteFunction() {
                    @Override
                    public String rewrite(String path) {
                        return path.replaceFirst("^/rest", "");
                    }
                });
        router.route("/rest", new HttpProxyRoute(proxyConfig));

        HttpProxyConfig proxyConfig2 = HttpProxyConfig.target("http://192.168.1.5:8088")
                .upgrade(true)
                .connectionTimeout(3000)
                .readTimeout(5000)
                .changeOrigin(true)
                .addHeader("origin", "http://192.168.1.5:8088")
                .rewrite(true);
        router.route("/zk", new HttpProxyRoute(proxyConfig2));

        // 添加一个ws资源,返回一个websocket资源句柄
        router.ws("/ws", new WebSocketResource(30) {
            public void onOpen(WebSocketConnection connection) {
                System.out.println("on open");
            }

            public void onMessage(WebSocketConnection connection, String message) throws IOException {
                System.out.println("onMessage: " + message);
                connection.sendText("wwwaaaaaaaaaaaaa");
                // connection.ping();
            }

            @Override
            public void onBinary(WebSocketConnection connection, byte[] data) throws IOException {
                System.out.println("onBinary: " + data.length);
                System.out.println("onBinary: " + new String(data, StandardCharsets.UTF_8));
            }

            public void onClose(WebSocketConnection connection, int code, String reason) {
                System.out.println("onClose: " + code + " " + reason);
            }

            @Override
            public void onErrorClose(WebSocketConnection connection) {
                System.out.println("onErrorClose: " + connection.id());
            }
        });

        // Static resource handler with 103 Early Hints for index page
        router.resource(new HttpResourceRoute("/", docBase)
                .earlyHints(
                        "<$base_path/upgrade.css>; rel=preload; as=style",
                        "<$base_path/loading.css>; rel=preload; as=style"
                ));

        // 404 handler
        router.notFoundHandler(new HttpRequestHandler() {
            @Override
            public void handle(HttpRequest request, HttpResponse response) throws Throwable {
                response.status(404).write("Custom 404".getBytes());
            }
        });

        HTTPServer.of(port)
                .pemSSL("cert/cert.pem", "cert/server.pem")
                .printReadErrorLog(true)
                .printSSLErrorLog(true)
                .printStackTraceError(true)
                .h2()
                .requestHandler(router).start();

        System.out.println("Router server started on https://localhost:" + port + contextPath + "/");
        System.out.println("  docBase: " + docBase);
        System.out.println("Test URLs:");
        System.out.println("  Exact match: https://localhost:" + port + contextPath + "/user");
        System.out.println("  Prefix match: https://localhost:" + port + contextPath + "/api/xxx");
        System.out.println("  Regex match: https://localhost:" + port + contextPath + "/v1/resource");
        System.out.println("  Static: https://localhost:" + port + contextPath + "/index.html#/login");
    }

}

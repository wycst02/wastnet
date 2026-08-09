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

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.InputStream;
import java.security.KeyStore;

/**
 * Test for HttpRouterHandler and HttpResourceRoute
 */
public class MiniNginx {

    public static void main(String[] args) throws Exception {
        // Configurable via system properties: -Dport=8080 -DcontextPath=/schedule-layout-ui -DdocBase=E:/2026_ws/failure-schedule/front-end/dist
        int port = Integer.getInteger("port", 8080);
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

        // Static resource handler
        router.resource(new HttpResourceRoute("/", docBase));

        // 404 handler
        router.notFoundHandler(new HttpRequestHandler() {
            @Override
            public void handle(HttpRequest request, HttpResponse response) throws Throwable {
                response.status(404).write("Custom 404".getBytes());
            }
        });

        HTTPServer.of(port)/*.sslContext(createSslContext())*/.requestHandler(router).start();

        System.out.println("Router server started on http://localhost:" + port + contextPath + "/");
        System.out.println("  docBase: " + docBase);
        System.out.println("Test URLs:");
        System.out.println("  Exact match: http://localhost:" + port + contextPath + "/user");
        System.out.println("  Prefix match: http://localhost:" + port + contextPath + "/api/xxx");
        System.out.println("  Regex match: http://localhost:" + port + contextPath + "/v1/resource");
        System.out.println("  Static: http://localhost:" + port + contextPath + "/index.html");
    }

    private static SSLContext createSslContext() throws Exception {
        // SSL
        char[] password = "123456".toCharArray();
        KeyStore keyStore = KeyStore.getInstance("JKS");

        // keytool -genkey -alias aliastest -keyalg RSA -keysize 1024 -keypass 123456 -validity 365 -keystore server.keystore -storepass 123456
        InputStream in = MiniNginx.class.getResourceAsStream("/server.keystore");
        keyStore.load(in, password);

        KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
        kmf.init(keyStore, password);
//        sslContext = SSLContext.getInstance("SSL");
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), null, null);

        return sslContext;
    }

}

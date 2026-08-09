package io.github.wycst.wastnet.examples.http.proxy;

import io.github.wycst.wastnet.http.HTTPServer;
import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.http.handler.HttpRouterHandler;
import io.github.wycst.wastnet.http.handler.HttpRoute;
import io.github.wycst.wastnet.http.proxy.HttpProxyConfig;
import io.github.wycst.wastnet.http.proxy.HttpProxyRoute;

/**
 * Test H2 → H2 proxy against a local wastnet H2 server.
 * <p>
 * Starts a target H2 server (port 9000) and a proxy (port 8000),
 * then prints curl commands to test the proxy chain.
 *
 * @author wangyc
 */
public class H2H2ProxyWastnetTest {

    public static void main(String[] args) throws Exception {
        int targetPort = 9000;
        int proxyPort = 8000;

        // 1. Target H2 server
        HttpRouterHandler targetHandler = new HttpRouterHandler("/");
        targetHandler.route("/", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.write(("Hello from wastnet H2 Server!\n"
                        + "Path: " + request.getUri()).getBytes());
                response.commit();
            }
        });

        HTTPServer target = HTTPServer.of(targetPort)
                .pemSSL("cert/cert.pem", "cert/server.pem")
                .h2()
                // .applicationProtocols("http/1.1")
                .requestHandler(targetHandler)
                .startupBannerEnabled(false)
                .start();
        System.out.println("[Target] H2 server on https://localhost:" + targetPort + "/");

        // 2. Proxy
        HttpProxyConfig proxyConfig = HttpProxyConfig.target("https://localhost:" + targetPort)
                .http2(true);

        HttpRouterHandler proxyRouter = new HttpRouterHandler("/");
        proxyRouter.route("/", new HttpProxyRoute(proxyConfig));

        HTTPServer proxy = HTTPServer.of(proxyPort)
                .pemSSL("cert/cert.pem", "cert/server.pem")
                .h2()
                .requestHandler(proxyRouter)
                .startupBannerEnabled(false)
                .start();
        System.out.println("[Proxy] H2 → H2 proxy on https://localhost:" + proxyPort + "/");

        System.out.println();
        System.out.println("Test commands:");
        System.out.println("  Direct to target:  curl --insecure --http2 https://localhost:" + targetPort + "/");
        System.out.println("  Via proxy:         curl --insecure --http2 https://localhost:" + proxyPort + "/");
        System.out.println();
        System.out.println("Press Ctrl+C to stop.");
    }
}

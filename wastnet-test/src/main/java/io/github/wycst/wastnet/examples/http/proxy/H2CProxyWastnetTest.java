package io.github.wycst.wastnet.examples.http.proxy;

import io.github.wycst.wastnet.http.HTTPServer;
import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.http.handler.HttpRouterHandler;
import io.github.wycst.wastnet.http.handler.HttpRoute;
import io.github.wycst.wastnet.http.proxy.HttpProxyConfig;
import io.github.wycst.wastnet.http.proxy.HttpProxyRoute;

/**
 * Test H2 → h2c proxy against a local wastnet h2c server.
 * <p>
 * Starts a plain HTTP/2 (h2c) target server on port 9000, then starts an
 * HTTPS + H2 proxy on port 8000 that forwards to the target using h2c prior knowledge.
 * <p>
 * The h2c target does NOT use TLS — the proxy connects via cleartext H2.
 *
 * @author wangyc
 */
public class H2CProxyWastnetTest {

    public static void main(String[] args) throws Exception {
        int targetPort = 9000;
        int proxyPort = 8000;

        // 1. Target h2c server (plain HTTP/2, no TLS)
        HttpRouterHandler targetHandler = new HttpRouterHandler("/");
        targetHandler.route("/", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.body("Hello from wastnet h2c Server!\n"
                        + "Path: " + request.getUri() + " httpVersion: " + request.getHttpVersion());
            }
        });

        HTTPServer.of(targetPort)
                .applicationProtocols("h2c")
                .requestHandler(targetHandler)
                .startupBannerEnabled(false)
                .start();
        System.out.println("[Target] h2c server on http://localhost:" + targetPort + "/");

        // 2. Proxy (HTTPS + H2 → http + h2c)
        HttpProxyConfig proxyConfig = HttpProxyConfig.target("http://localhost:" + targetPort)
                .http2(true)
                .h2c(true);

        HttpRouterHandler proxyRouter = new HttpRouterHandler("/");
        proxyRouter.route("/", new HttpProxyRoute(proxyConfig));

        HTTPServer proxy = HTTPServer.of(proxyPort)
                .pemSSL("cert/cert.pem", "cert/server.pem")
                .h2()
                .requestHandler(proxyRouter)
                .startupBannerEnabled(false)
                .start();
        System.out.println("[Proxy] H2 → h2c proxy on https://localhost:" + proxyPort + "/");

        System.out.println();
        System.out.println("Test commands:");
        System.out.println("  Direct to target (h2c prior knowledge):");
        System.out.println("    curl --http2-prior-knowledge http://localhost:" + targetPort + "/");
        System.out.println("  Via proxy (HTTPS + H2 → h2c):");
        System.out.println("    curl --insecure --http2 https://localhost:" + proxyPort + "/");
        System.out.println();
        System.out.println("Press Ctrl+C to stop.");
    }
}

package io.github.wycst.wastnet.examples.http.proxy;

import io.github.wycst.wastnet.http.HTTPServer;
import io.github.wycst.wastnet.http.handler.HttpRouterHandler;
import io.github.wycst.wastnet.http.proxy.HttpProxyConfig;
import io.github.wycst.wastnet.http.proxy.HttpProxyRoute;

/**
 * Test for HTTP/2 to HTTP/2 proxy targeting registry.npmmirror.com (supports h2).
 *
 * @author wangyc
 */
public class H2H2ProxyNpmMirrorTest {

    public static void main(String[] args) {
        int port = Integer.getInteger("port", 8001);

        HttpProxyConfig proxyConfig = HttpProxyConfig.target("https://registry.npmmirror.com")
                .http2(true);

        HttpRouterHandler router = new HttpRouterHandler("/");
        router.route("/", new HttpProxyRoute(proxyConfig));

        HTTPServer.of(port)
                .pemSSL("cert/cert.pem", "cert/server.pem")
                .h2()
                .requestHandler(router)
                .startupBannerEnabled(false)
                .printStackTraceError(true)
                .start();

        System.out.println("H2 -> H2 proxy (npmmirror) started on https://localhost:" + port + "/");
        System.out.println("  Proxy target: https://registry.npmmirror.com");
        System.out.println("  Test: curl --insecure --http2 https://localhost:" + port + "/");
    }
}

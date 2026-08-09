package io.github.wycst.wastnet.examples.http.proxy;

import io.github.wycst.wastnet.http.HTTPServer;
import io.github.wycst.wastnet.http.handler.HttpRouterHandler;
import io.github.wycst.wastnet.http.proxy.HttpProxyConfig;
import io.github.wycst.wastnet.http.proxy.HttpProxyRoute;

/**
 * Test for HTTP/2 to HTTP/2 proxy targeting nghttp2.org (known H2 server).
 *
 * @author wangyc
 */
public class H2H2ProxyNghttp2Test {

    public static void main(String[] args) {
        int port = Integer.getInteger("port", 8000);

        HttpProxyConfig proxyConfig = HttpProxyConfig.target("https://nghttp2.org")
                .http2(true);

        HttpRouterHandler router = new HttpRouterHandler("/");
        router.route("/", new HttpProxyRoute(proxyConfig));

        HTTPServer.of(port)
                .pemSSL("cert/cert.pem", "cert/server.pem")
                .h2()
                .requestHandler(router)
                .startupBannerEnabled(false)
                .start();

        System.out.println("H2 -> H2 proxy (nghttp2.org) started on https://localhost:" + port + "/");
        System.out.println("  Client: curl --insecure --http2 https://localhost:" + port + "/");
    }
}

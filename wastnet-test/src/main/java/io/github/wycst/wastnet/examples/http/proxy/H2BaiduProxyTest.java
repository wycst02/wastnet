package io.github.wycst.wastnet.examples.http.proxy;

import io.github.wycst.wastnet.http.HTTPServer;
import io.github.wycst.wastnet.http.handler.HttpRouterHandler;
import io.github.wycst.wastnet.http.proxy.HttpProxyConfig;
import io.github.wycst.wastnet.http.proxy.HttpProxyRoute;

/**
 * Test for H2 → H1 proxy to Baidu.
 * <p>
 * Proxy listens with HTTPS + ALPN h2, forwards to Baidu via HTTP/1.1.
 *
 * @author wangyc
 */
public class H2BaiduProxyTest {

    public static void main(String[] args) {
        int port = Integer.getInteger("port", 8000);

        HttpProxyConfig proxyConfig = HttpProxyConfig.target("https://www.baidu.com");

        HttpRouterHandler router = new HttpRouterHandler("/");
        router.route("/", new HttpProxyRoute(proxyConfig));

        HTTPServer.of(port)
                .pemSSL("cert/cert.pem", "cert/server.pem")
                .h2()
                .requestHandler(router)
                .startupBannerEnabled(false)
                .start();

        System.out.println("H2 -> H1 proxy (Baidu) started on https://localhost:" + port + "/");
        System.out.println("  Proxy target: https://www.baidu.com");
        System.out.println("  Test: curl --insecure --http2 https://localhost:" + port + "/s?wd=hello");
    }
}

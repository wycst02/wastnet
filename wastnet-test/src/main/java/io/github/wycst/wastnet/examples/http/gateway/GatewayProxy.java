package io.github.wycst.wastnet.examples.http.gateway;

import io.github.wycst.wastnet.http.HTTPServer;
import io.github.wycst.wastnet.http.HttpOptions;
import io.github.wycst.wastnet.http.handler.HttpResourceRoute;
import io.github.wycst.wastnet.http.handler.HttpRouterHandler;
import io.github.wycst.wastnet.http.proxy.HttpProxyConfig;
import io.github.wycst.wastnet.socket.tcp.NioConfig;

/**
 * Gateway edge: terminates TLS + HTTP/2 on :8443, serves /static/* from local
 * disk (per the gateway spec), and reverse-proxies the remaining dynamic
 * requests to the wastnet backend on 127.0.0.1:8082 over h2c.
 * Used by compose.gateway.yml (the "proxy" service).
 */
public final class GatewayProxy {
    private static final String CERT_FILE = "cert/cert.pem";
    private static final String KEY_FILE = "cert/server.pem";
    private static final String STATIC_DIR = "E:\\tmp\\data\\static";

    public static void main(String[] args) throws Exception {

        HttpRouterHandler router = new HttpRouterHandler("/");

        // Serve /static/* from local disk (gateway spec requires the proxy to
        // serve static files directly, not forward them to the backend). The
        // backend's /static handler is never reached for these paths.
        router.resource(new HttpResourceRoute("/static", STATIC_DIR).detectPreCompressed(true));

        // proxy 8080
        router.proxy("/", HttpProxyConfig.target("http://127.0.0.1:8080"));

        NioConfig nioConfig = new NioConfig();
        // Benchmark measures raw throughput: disable response compression.
        nioConfig.option(HttpOptions.GZIP, false);

        HTTPServer.of(8443, nioConfig)
                .pemSSL(CERT_FILE, KEY_FILE)
                .pemSSL("cert/cert.pem", "cert/server.pem")
                .h2()
                .requestHandler(router)
                .start();

        System.out.println("[Gateway] edge proxy h2/TLS :8443 -> 127.0.0.1:8082 (h2c)");
        Thread.currentThread().join();
    }
}
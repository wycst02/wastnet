package io.github.wycst.wastnet.benchmarks.http;

import io.github.wycst.wastnet.http.HTTPServer;
import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.http.handler.HttpRequestHandler;
import io.github.wycst.wastnet.socket.tcp.NioConfig;

/**
 * wastnet benchmark server.
 * h1: plain HTTP/1.1. h1p: h1 with pipelining enabled. h2: HTTP/2 over TLS. h2c: cleartext HTTP/2.
 */
public class WastnetServer implements BenchmarkServer {

    private final boolean http2;
    private final boolean h2Tls;
    private HTTPServer server;

    public WastnetServer(boolean http2, boolean h2Tls) {
        this.http2 = http2;
        this.h2Tls = h2Tls;
    }

    private HttpRequestHandler handler() {
        return new HttpRequestHandler() {
            @Override
            public void handle(HttpRequest request, HttpResponse response) throws Throwable {
                response.contentType("text/plain;charset=utf-8").body("hello world");
            }
        };
    }

    @Override
    public void start(int port) throws Exception {
        NioConfig nioConfig = new NioConfig().testMode(); // 测试模式支持pipeline(h1)
        HTTPServer s = HTTPServer.of(port, nioConfig);
        if (http2) {
            if (h2Tls) {
                // HTTP/2 over TLS (browser-supported h2); pemSSL enables SSL, h2() sets ALPN.
                s.h2().pemSSL("cert/cert.pem", "cert/server.pem");
            } else {
                // cleartext HTTP/2 (h2c): prior-knowledge, declare h2c per docs.
                s.applicationProtocols("h2c");
            }
        }
        s.requestHandler(handler());
        server = s.start();
    }

    @Override
    public void stop() throws Exception {
        if (server != null) {
            server.shutdown();
        }
    }
}

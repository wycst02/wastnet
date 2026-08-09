package io.github.wycst.wastnet.benchmarks.http;

import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.util.Headers;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;

import io.github.wycst.wastnet.socket.tcp.PEMSSLContextFactory;

import javax.net.ssl.SSLContext;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Undertow (2.2.x, last Java 8 compatible branch) benchmark server.
 * h1: plain HTTP/1.1. h2: HTTP/2 over TLS (certs bundled in jar).
 */
public class UndertowServer implements BenchmarkServer {

    private final boolean http2;
    private final boolean h2Tls;
    private final AtomicReference<Undertow> ref = new AtomicReference<Undertow>(null);

    public UndertowServer(boolean http2, boolean h2Tls) {
        this.http2 = http2;
        this.h2Tls = h2Tls;
    }

    private HttpHandler handler() {
        return new HttpHandler() {
            @Override
            public void handleRequest(HttpServerExchange exchange) throws Exception {
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain;charset=utf-8");
                exchange.getResponseSender().send("hello world");
            }
        };
    }

    @Override
    public void start(int port) throws Exception {
        Undertow.Builder builder = Undertow.builder().setHandler(handler());
        if (h2Tls) {
            SSLContext sslContext = new PEMSSLContextFactory("cert/cert.pem", "cert/server.pem").create();
            builder.addHttpsListener(port, "0.0.0.0", sslContext);
            builder.setServerOption(UndertowOptions.ENABLE_HTTP2, true);
        } else {
            builder.addHttpListener(port, "0.0.0.0");
            if (http2) {
                builder.setServerOption(UndertowOptions.ENABLE_HTTP2, true);
            }
        }
        Undertow server = builder.build();
        server.start();
        ref.set(server);
    }

    @Override
    public void stop() throws Exception {
        Undertow server = ref.get();
        if (server != null) {
            server.stop();
        }
    }
}

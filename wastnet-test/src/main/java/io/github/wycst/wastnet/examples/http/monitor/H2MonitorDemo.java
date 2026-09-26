package io.github.wycst.wastnet.examples.http.monitor;

import io.github.wycst.wast.json.JSON;
import io.github.wycst.wastnet.http.HTTPServer;
import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.http.annotation.AnnotationRouterHandler;
import io.github.wycst.wastnet.http.annotation.ConverterConfig;
import io.github.wycst.wastnet.http.annotation.HttpMessageConverter;
import io.github.wycst.wastnet.http.h2.H2Monitor;
import io.github.wycst.wastnet.socket.tcp.NioConfig;

/**
 * Demonstrates {@link H2Monitor} with an HTTP/2 server.
 * <p>
 * Start with: {@code -Dwastnet.h2.monitor=true}
 * <p>
 * Endpoints:
 * <ul>
 *   <li>{@code GET /h2monitor/global} — snapshot of all H2 connections</li>
 * </ul>
 *
 * @author wangyc
 */
public class H2MonitorDemo {

    public static void main(String[] args) throws Exception {
        System.setProperty("wastnet.h2.monitor", "true");
        AnnotationRouterHandler router = new AnnotationRouterHandler()
                .messageConverter(new HttpMessageConverter() {
                    @Override
                    public void write(Object value, ConverterConfig config, HttpResponse response) throws Exception {
                        response.contentType("application/json;charset=utf-8")
                                .body(JSON.toJsonBytes(value));
                    }

                    @Override
                    public Object read(io.github.wycst.wastnet.http.HttpRequest request,
                                      ConverterConfig config, java.lang.reflect.Type type) {
                        return null;
                    }
                })
                .scanPackages("io.github.wycst.wastnet.examples.http.monitor");

        NioConfig nioConfig = new NioConfig();
        HTTPServer.of(8080, nioConfig)
                .pemSSL("cert/cert.pem", "cert/server.pem")
                .h2()
                .bufferSize(1024 * 16)
                .requestHandler(router)
                .start();

        System.out.println("H2Monitor demo: https://localhost:8080/h2monitor/global");
    }
}

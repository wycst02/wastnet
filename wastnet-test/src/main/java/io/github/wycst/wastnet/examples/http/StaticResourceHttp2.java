package io.github.wycst.wastnet.examples.http;

import io.github.wycst.wastnet.http.HTTPServer;
import io.github.wycst.wastnet.http.HttpOptions;
import io.github.wycst.wastnet.http.handler.HttpResourceRoute;
import io.github.wycst.wastnet.http.handler.HttpRouterHandler;
import io.github.wycst.wastnet.socket.tcp.NioConfig;

public class StaticResourceHttp2 {

    public static void main(String[] args) throws Exception {
        final HttpRouterHandler router = new HttpRouterHandler();
        router.resource(new HttpResourceRoute("/", "E:\\tmp\\data").detectPreCompressed(true));
        HTTPServer.of(8080, new NioConfig().testMode())
                .option(HttpOptions.GZIP, true)
                .option(HttpOptions.HTTP2_EXPOSE_DATE_HEADER, false)
                .pemSSL("cert/cert.pem", "cert/server.pem")
                .h2()
                .requestHandler(router).start();
    }

}

package io.github.wycst.wastnet.examples.http;

import io.github.wycst.wastnet.http.HTTPServer;
import io.github.wycst.wastnet.http.HttpHeaderNames;
import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.http.handler.HttpRequestHandler;
import io.github.wycst.wastnet.socket.tcp.NioConfig;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.InputStream;
import java.security.KeyStore;

/**
 * @since 2024-1-19
 * @author wangyc
 */
public class HttpServerTest {

    public static void main(String[] args) throws Exception {
        NioConfig nioConfig = new NioConfig();
        nioConfig.testMode();
        HTTPServer.of(8080, nioConfig)
                        .sslContext(createSslContext())
                        .h2()
                        .bufferSize(1024 * 16)
                        .printReadErrorLog(true)
                        .requestHandler(new HttpRequestHandler() {
                            @Override
                            public void handle(HttpRequest request, HttpResponse response) throws Throwable {
                                response.body("aa");
                            }
                        }).start();
    }

    private static SSLContext createSslContext() throws Exception {
        // SSL
        char[] password = "123456".toCharArray();
        KeyStore keyStore = KeyStore.getInstance("JKS");

        // keytool -genkey -alias aliastest -keyalg RSA -keysize 1024 -keypass 123456 -validity 365 -keystore server.keystore -storepass 123456
        InputStream in = HttpServerTest.class.getResourceAsStream("/server.keystore");
        keyStore.load(in, password);

        KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
        kmf.init(keyStore, password);
//        sslContext = SSLContext.getInstance("SSL");
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), null, null);

        return sslContext;
    }

}

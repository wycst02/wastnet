package io.github.wycst.wastnet.examples.http;

import io.github.wycst.wast.log.Log;
import io.github.wycst.wast.log.LogFactory;
import io.github.wycst.wastnet.http.HTTPServer;
import io.github.wycst.wastnet.http.HttpConf;
import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.http.handler.HttpRequestHandler;

import java.util.Arrays;

/**
 * 自动 GZIP 压缩功能测试
 * 测试框架自动应用 GZIP 压缩的能力
 */
public class AutoGzipTest {

    private static final Log log = LogFactory.getLog(AutoGzipTest.class);

    public static void main(String[] args) throws Exception {
        startTestServer();
    }

    /**
     * 启动测试服务器
     */
    public static void startTestServer() throws Exception {
        HTTPServer server = HTTPServer.of(8086)
                .requestHandler(new GzipTestHandler())
                .start();

        log.info("Auto GZIP test server started on port {}", server.getPort());
        log.info("Configuration:");
        log.info("  GZIP enabled: {}", HttpConf.GZIP);
        log.info("  GZIP_MIN_SIZE: {} bytes", HttpConf.GZIP_MIN_SIZE);
        log.info("\nTest endpoints:");
        log.info("  - Small data (<2KB): http://localhost:{}/small", server.getPort());
        log.info("  - Large data (>=2KB): http://localhost:{}/large", server.getPort());
        log.info("  - No compression check: http://localhost:{}/no-compress", server.getPort());
        log.info("\nTest commands:");
        log.info("  # With compression");
        log.info("  curl -H 'Accept-Encoding: gzip' -v http://localhost:{}/large", server.getPort());
        log.info("  curl -H 'Accept-Encoding: gzip' -v http://localhost:{}/small", server.getPort());
        log.info("  # Without compression");
        log.info("  curl -H 'Accept-Encoding: identity' -v http://localhost:{}/large", server.getPort());
    }

    /**
     * GZIP测试处理器
     */
    static class GzipTestHandler implements HttpRequestHandler {

        @Override
        public void handle(HttpRequest request, HttpResponse response) throws Throwable {
            String uri = request.getRequestUri();
            String acceptEncoding = request.getHeader("Accept-Encoding");
            boolean gzipSupported = acceptEncoding != null && acceptEncoding.contains("gzip");

            log.info("Request: {} | Accept-Encoding: {} | GZIP supported: {}",
                    uri, acceptEncoding, gzipSupported);

            switch (uri) {
                case "/small":
                    // 小数据（小于 GZIP_MIN_SIZE，不压缩）
                    handleSmallData(response);
                    break;

                case "/large":
                    // 大数据（大于等于 GZIP_MIN_SIZE，自动压缩）
                    handleLargeData(response);
                    break;

                case "/no-compress":
                    // 手动设置了 Content-Length，不压缩（因为有 flushCount 检查）
                    handleNoCompress(response);
                    break;

                default:
                    sendHelpPage(response);
                    break;
            }
        }

        /**
         * 处理小数据（<2KB）
         * 期望：不应用 GZIP 压缩
         */
        private void handleSmallData(HttpResponse response) {
            byte[] data = buildData(1024); // 1KB
            response.contentType("text/plain;charset=utf-8")
                    .body(data);
            log.info("Small data response: {} bytes (should NOT be compressed)", data.length);
        }

        /**
         * 处理大数据（>=2KB）
         * 期望：自动应用 GZIP 压缩
         */
        private void handleLargeData(HttpResponse response) {
            byte[] data = buildData(4096); // 4KB
            response.contentType("text/plain;charset=utf-8")
                    .body(data);
            log.info("Large data response: {} bytes (should be auto-compressed)", data.length);
        }

        /**
         * 处理不压缩场景（手动 flush）
         * 期望：不应用 GZIP 压缩（因为不是干净管道）
         */
        private void handleNoCompress(HttpResponse response) {
            byte[] data = buildData(4096); // 4KB
            response.contentType("text/plain;charset=utf-8")
                    .contentLength(data.length)
                    .body(data);
            // 如果应用层调用了 flush，框架不会自动压缩
            log.info("No compress response: {} bytes (should NOT be compressed)", data.length);
        }

        /**
         * 生成测试数据
         */
        private byte[] buildData(int size) {
            byte[] bytes = new byte[size];
            Arrays.fill(bytes, (byte) 'x');
            return bytes;
        }

        /**
         * 发送帮助页面
         */
        private void sendHelpPage(HttpResponse response) {
            String html = "<html><head><title>Auto GZIP Test</title></head><body>" +
                    "<h1>Auto GZIP Compression Test</h1>" +
                    "<h2>Configuration:</h2>" +
                    "<p>GZIP enabled: <b>" + HttpConf.GZIP + "</b></p>" +
                    "<p>GZIP_MIN_SIZE: <b>" + HttpConf.GZIP_MIN_SIZE + "</b> bytes</p>" +
                    "<h2>Test Endpoints:</h2>" +
                    "<ul>" +
                    "<li><a href='/small'>/small</a> - Small data (&lt;2KB, no compression)</li>" +
                    "<li><a href='/large'>/large</a> - Large data (&gt;=2KB, auto-compression)</li>" +
                    "<li><a href='/no-compress'>/no-compress</a> - No compress (manual flush)</li>" +
                    "</ul>" +
                    "<h2>Test Commands:</h2>" +
                    "<pre>" +
                    "# With GZIP compression\n" +
                    "curl -H 'Accept-Encoding: gzip' -v http://localhost:8086/large\n\n" +
                    "# Without compression\n" +
                    "curl -H 'Accept-Encoding: identity' -v http://localhost:8086/large\n" +
                    "</pre>" +
                    "</body></html>";

            response.contentType("text/html;charset=utf-8")
                    .body(html.getBytes());
        }
    }
}

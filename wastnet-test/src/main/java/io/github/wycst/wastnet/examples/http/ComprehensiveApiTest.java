package io.github.wycst.wastnet.examples.http;

import io.github.wycst.wast.log.Log;
import io.github.wycst.wast.log.LogFactory;
import io.github.wycst.wastnet.http.HTTPServer;
import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.http.HttpStatus;
import io.github.wycst.wastnet.http.handler.HttpRequestHandler;
import io.github.wycst.wastnet.socket.tcp.NioConfig;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HTTP Response API 综合特性测试
 * 演示各种高级特性和最佳实践
 *
 * @author wangyc
 */
public class ComprehensiveApiTest {

    private static final Log log = LogFactory.getLog(ComprehensiveApiTest.class);
    private static final AtomicInteger requestCounter = new AtomicInteger(0);

    public static void main(String[] args) throws Exception {
        testComprehensiveFeatures();
    }

    /**
     * 测试综合特性
     */
    public static void testComprehensiveFeatures() throws Exception {
        NioConfig nioConfig = new NioConfig();
        nioConfig.testMode();
        nioConfig.setWorkerNum(4);

        HTTPServer httpServer = HTTPServer.of(8085, nioConfig)
                .bufferSize(2048)
                .requestHandler(new ComprehensiveRequestHandler())
                .start();

        log.info("Comprehensive API test server started on port " + httpServer.getPort());
        log.info("Test endpoints:");
        log.info("  Home: http://localhost:8085/");
        log.info("  Headers demo: http://localhost:8085/headers");
        log.info("  Status codes: http://localhost:8085/status/404");
        log.info("  Content negotiation: http://localhost:8085/negotiate");
        log.info("  Performance test: http://localhost:8085/performance");
        log.info("  Error handling: http://localhost:8085/error");
    }

    /**
     * 综合请求处理器
     */
    static class ComprehensiveRequestHandler implements HttpRequestHandler {
        private final Map<String, String> userData = new HashMap<String, String>();

        public void handle(HttpRequest request, HttpResponse response) throws Throwable {
            int requestId = requestCounter.incrementAndGet();
            String uri = request.getRequestUri();

            log.info("Request #" + requestId + ": " + request.getMethod() + " " + uri);

            try {
                if ("/".equals(uri)) {
                    handleHome(request, response);
                } else if ("/headers".equals(uri)) {
                    handleHeadersDemo(request, response);
                } else if (uri.startsWith("/status/")) {
                    handleStatusCodes(request, response);
                } else if ("/negotiate".equals(uri)) {
                    handleContentNegotiation(request, response);
                } else if ("/performance".equals(uri)) {
                    handlePerformanceTest(request, response);
                } else if ("/error".equals(uri)) {
                    handleErrorHandling(request, response);
                } else if ("/counter".equals(uri)) {
                    handleCounter(request, response);
                } else {
                    handleNotFound(request, response);
                }
            } catch (Exception e) {
                log.error("Request #" + requestId + " failed", e);
                handleUnexpectedError(request, response, e);
            }
        }

        /**
         * 主页
         */
        private void handleHome(HttpRequest request, HttpResponse response) throws Throwable {
            StringBuilder html = new StringBuilder();
            html.append("<!DOCTYPE html><html><head><title>API Test Suite</title></head><body>");
            html.append("<h1>HTTP Response API Test Suite</h1>");
            html.append("<p>Total requests processed: " + requestCounter.get() + "</p>");
            html.append("<h2>Available Tests:</h2><ul>");
            html.append("<li><a href='/headers'>Headers Management</a></li>");
            html.append("<li><a href='/status/404'>Status Codes</a></li>");
            html.append("<li><a href='/negotiate'>Content Negotiation</a></li>");
            html.append("<li><a href='/performance'>Performance Test</a></li>");
            html.append("<li><a href='/error'>Error Handling</a></li>");
            html.append("<li><a href='/counter'>Request Counter</a></li>");
            html.append("</ul></body></html>");

            response.status(HttpStatus.OK)
                    .contentType("text/html;charset=utf-8")
                    .body(html.toString().getBytes());
        }

        /**
         * 响应头管理演示
         */
        private void handleHeadersDemo(HttpRequest request, HttpResponse response) throws Throwable {
            // 添加多种类型的响应头
            response.addHeader("X-Request-ID", String.valueOf(requestCounter.get()));
            response.addHeader("X-Powered-By", "WastNet");
            response.addHeader("Cache-Control", "no-cache");
            response.addHeader("Cache-Control", "no-store");
            response.addHeader("X-Custom-Header", "Custom Value");
            response.addHeader("X-Timestamp", String.valueOf(System.currentTimeMillis()));

            response.contentType("application/json;charset=utf-8")
                    .status(HttpStatus.OK);

            String jsonResponse = "{\n" +
                    "  \"message\": \"Headers demo\",\n" +
                    "  \"requestId\": " + requestCounter.get() + ",\n" +
                    "  \"headersAdded\": 6,\n" +
                    "  \"features\": [\n" +
                    "    \"Multiple headers with same key\",\n" +
                    "    \"Chainable header setting\",\n" +
                    "    \"Automatic header management\",\n" +
                    "    \"Custom headers support\"\n" +
                    "  ]\n" +
                    "}";

            response.body(jsonResponse.getBytes());
        }

        /**
         * 状态码测试
         */
        private void handleStatusCodes(HttpRequest request, HttpResponse response) throws Throwable {
            String statusCodeStr = request.getRequestUri().substring("/status/".length());
            int statusCode;

            try {
                statusCode = Integer.parseInt(statusCodeStr);
            } catch (NumberFormatException e) {
                statusCode = 400;
            }

            HttpStatus status = getStatusByCode(statusCode);
            if (status == null) {
                status = HttpStatus.NOT_FOUND;
            }

            response.status(status);

            String message;
            switch (status) {
                case OK:
                    message = "Everything is working perfectly!";
                    response.contentType("text/plain;charset=utf-8");
                    break;
                case NOT_FOUND:
                    message = "The resource you requested could not be found.";
                    response.contentType("text/plain;charset=utf-8");
                    break;
                case BAD_REQUEST:
                    message = "Your request was malformed or invalid.";
                    response.contentType("text/plain;charset=utf-8");
                    break;
                case INTERNAL_SERVER_ERROR:
                    message = "An unexpected error occurred on the server.";
                    response.contentType("text/plain;charset=utf-8");
                    break;
                default:
                    message = "Status: " + status.text + " (" + status.code + ")";
                    response.contentType("text/plain;charset=utf-8");
                    break;
            }

            response.body(message.getBytes());
        }

        /**
         * 根据状态码获取HttpStatus枚举
         */
        private HttpStatus getStatusByCode(int code) {
            for (HttpStatus status : HttpStatus.values()) {
                if (status.code == code) {
                    return status;
                }
            }
            return null;
        }

        /**
         * 内容协商演示
         */
        private void handleContentNegotiation(HttpRequest request, HttpResponse response) throws Throwable {
            String acceptHeader = request.getHeader("Accept");
            String userAgent = request.getHeader("User-Agent");

            String contentType;
            String responseBody;

            if (acceptHeader != null && acceptHeader.contains("application/json")) {
                contentType = "application/json;charset=utf-8";
                responseBody = "{\n" +
                        "  \"format\": \"JSON\",\n" +
                        "  \"preferred\": true,\n" +
                        "  \"client\": \"" + (userAgent != null ? userAgent : "Unknown") + "\",\n" +
                        "  \"serverTime\": " + System.currentTimeMillis() + "\n" +
                        "}";
            } else if (acceptHeader != null && acceptHeader.contains("text/xml")) {
                contentType = "text/xml;charset=utf-8";
                responseBody = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                        "<response>\n" +
                        "    <format>XML</format>\n" +
                        "    <preferred>true</preferred>\n" +
                        "    <client>" + (userAgent != null ? userAgent : "Unknown") + "</client>\n" +
                        "    <serverTime>" + System.currentTimeMillis() + "</serverTime>\n" +
                        "</response>";
            } else {
                contentType = "text/plain;charset=utf-8";
                responseBody = "Format: Plain Text\nClient: " +
                        (userAgent != null ? userAgent : "Unknown") +
                        "\nServer Time: " + System.currentTimeMillis();
            }

            response.status(HttpStatus.OK)
                    .contentType(contentType)
                    .header("Vary", "Accept")
                    .body(responseBody.getBytes());
        }

        /**
         * 性能测试
         */
        private void handlePerformanceTest(HttpRequest request, HttpResponse response) throws Throwable {
            long startTime = System.nanoTime();

            int dataSize = 10000;
            StringBuilder data = new StringBuilder();
            for (int i = 0; i < dataSize; ++i) {
                data.append("Performance test data item #").append(i).append("\n");
            }

            long endTime = System.nanoTime();
            long processingTime = (endTime - startTime) / 1_000_000;

            String responseText = String.format(
                    "Performance Test Results:\n" +
                            "Data items generated: %d\n" +
                            "Processing time: %d ms\n" +
                            "Average time per item: %.2f μs\n" +
                            "Request ID: %d",
                    dataSize, processingTime, (processingTime * 1000.0) / dataSize, requestCounter.get()
            );

            response.status(HttpStatus.OK)
                    .contentType("text/plain;charset=utf-8")
                    .body(responseText.getBytes());
        }

        /**
         * 错误处理演示
         */
        private void handleErrorHandling(HttpRequest request, HttpResponse response) throws Throwable {
            String errorType = request.getParameter("type");

            if ("business".equals(errorType)) {
                response.setStatus(HttpStatus.BAD_REQUEST);
                response.setContentType("application/json;charset=utf-8");
                response.body(("{\n" +
                        "  \"error\": \"BUSINESS_ERROR\",\n" +
                        "  \"message\": \"Invalid business parameters\",\n" +
                        "  \"code\": 40001\n" +
                        "}").getBytes());
            } else if ("validation".equals(errorType)) {
                response.setStatus(HttpStatus.UNPROCESSABLE_ENTITY);
                response.setContentType("application/json;charset=utf-8");
                response.body(("{\n" +
                        "  \"error\": \"VALIDATION_FAILED\",\n" +
                        "  \"message\": \"Request data validation failed\",\n" +
                        "  \"details\": [\"Field 'email' is required\", \"Field 'age' must be positive\"],\n" +
                        "  \"code\": 42201\n" +
                        "}").getBytes());
            } else {
                throw new RuntimeException("Simulated internal server error for testing");
            }
        }

        /**
         * 计数器演示
         */
        private void handleCounter(HttpRequest request, HttpResponse response) throws Throwable {
            String action = request.getParameter("action");
            String key = request.getParameter("key");

            if (key == null || key.trim().isEmpty()) {
                key = "default";
            }

            if ("increment".equals(action)) {
                String oldValue = userData.get(key);
                if (oldValue == null) {
                    userData.put(key, "1");
                } else {
                    int newValue = Integer.parseInt(oldValue) + 1;
                    userData.put(key, String.valueOf(newValue));
                }
            } else if ("reset".equals(action)) {
                userData.put(key, "0");
            }

            int currentValue = Integer.parseInt(userData.getOrDefault(key, "0"));

            response.status(HttpStatus.OK)
                    .contentType("application/json;charset=utf-8")
                    .body(String.format("{\n" +
                            "  \"key\": \"%s\",\n" +
                            "  \"value\": %d,\n" +
                            "  \"action\": \"%s\"\n" +
                            "}", key, currentValue, action != null ? action : "get").getBytes());
        }

        /**
         * 404处理
         */
        private void handleNotFound(HttpRequest request, HttpResponse response) throws Throwable {
            response.status(HttpStatus.NOT_FOUND)
                    .contentType("text/html;charset=utf-8")
                    .body(("<html><body>\n" +
                            "<h1>404 - Page Not Found</h1>\n" +
                            "<p>The requested resource was not found on this server.</p>\n" +
                            "<a href=\"/\">Return to home</a>\n" +
                            "</body></html>").getBytes());
        }

        /**
         * 未预期错误处理
         */
        private void handleUnexpectedError(HttpRequest request, HttpResponse response, Exception e) throws Throwable {
            response.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType("application/json;charset=utf-8")
                    .body(("{\n" +
                            "  \"error\": \"INTERNAL_SERVER_ERROR\",\n" +
                            "  \"message\": \"An unexpected error occurred\",\n" +
                            "  \"requestId\": " + requestCounter.get() + "\n" +
                            "}").getBytes());
        }
    }
}

package io.github.wycst.wastnet.examples.http;

import io.github.wycst.wast.log.Log;
import io.github.wycst.wast.log.LogFactory;
import io.github.wycst.wastnet.http.HTTPServer;
import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.http.HttpStatus;
import io.github.wycst.wastnet.http.handler.HttpExceptionHandler;
import io.github.wycst.wastnet.http.handler.HttpRequestHandler;
import io.github.wycst.wastnet.socket.tcp.NioConfig;

import java.util.concurrent.CompletableFuture;

/**
 * HTTP Response API 使用示例测试类
 * 基于文档中的各种使用场景展示API的正确用法
 *
 * @author wangyc
 */
public class ResponseApiExamplesTest {

    private static final Log log = LogFactory.getLog(ResponseApiExamplesTest.class);

    public static void main(String[] args) throws Exception {
        testAllExamples();
    }

    /**
     * 测试所有API使用示例
     */
    public static void testAllExamples() throws Exception {
        NioConfig nioConfig = new NioConfig();
        nioConfig.testMode();
        nioConfig.setWorkerNum(4);

        HTTPServer httpServer = HTTPServer.of(8080, nioConfig)
                .bufferSize(1024)
                .requestHandler(new CompositeRequestHandler())
                .exceptionHandler(new HttpExceptionHandler() {
                    public void handleException(HttpRequest request, HttpResponse response, Throwable throwable) {
                        log.error("Error processing request: " + throwable.getMessage(), throwable);
                        response.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                .contentType("text/plain;charset=utf-8")
                                .body(("Internal Server Error: " + throwable.getMessage()).getBytes());
                    }
                })
                .start();

        log.info("HTTP Server started on port " + httpServer.getPort());
        log.info("Test URLs:");
        log.info("  Basic response: http://localhost:8080/basic");
        log.info("  JSON API: http://localhost:8080/api/users");
        log.info("  File download: http://localhost:8080/download/example.txt");
        log.info("  Chunked stream: http://localhost:8080/stream/events");
        log.info("  Async processing: http://localhost:8080/async/process");
        log.info("  SSE (simple): http://localhost:8080/sse/simple");
        log.info("  SSE (emitter): http://localhost:8080/sse/emitter");
    }

    /**
     * 组合请求处理器，根据不同路径演示不同场景
     */
    static class CompositeRequestHandler implements HttpRequestHandler {
        public void handle(HttpRequest request, HttpResponse response) throws Throwable {
            String uri = request.getRequestUri();

            if ("/basic".equals(uri)) {
                handleBasicResponse(request, response);
            } else if ("/api/users".equals(uri)) {
                handleJsonApi(request, response);
            } else if ("/download/example.txt".equals(uri)) {
                handleFileDownload(request, response);
            } else if ("/stream/events".equals(uri)) {
                handleChunkedStream(request, response);
            } else if ("/async/process".equals(uri)) {
                handleAsyncProcessing(request, response);
            } else if ("/sse/simple".equals(uri)) {
                handleSseSimple(request, response);
            } else {
                handleDefault(request, response);
            }
        }

        /**
         * 基础同步响应示例
         */
        private void handleBasicResponse(HttpRequest request, HttpResponse response) throws Throwable {
            if(true) {
                throw new Exception("测试异常");
            }
            response.status(HttpStatus.OK)
                    .contentType("text/plain;charset=utf-8")
                    .body("Hello World!".getBytes());
        }

        /**
         * JSON API响应示例
         */
        private void handleJsonApi(HttpRequest request, HttpResponse response) throws Throwable {
            String json = "{\"message\":\"success\",\"data\":{\"id\":1,\"name\":\"John Doe\",\"email\":\"john@example.com\"}}";
            response.status(HttpStatus.OK)
                    .contentType("application/json;charset=utf-8")
                    .contentLength(json.getBytes().length)
                    .body(json.getBytes());
        }

        /**
         * 文件下载响应示例
         */
        private void handleFileDownload(HttpRequest request, HttpResponse response) throws Throwable {
            // 创建测试文件内容
            String fileContent = "This is a sample file for download testing.\n"
                    + "It demonstrates file download functionality.\n"
                    + "Current time: " + System.currentTimeMillis();

            byte[] fileBytes = fileContent.getBytes();

            response.status(HttpStatus.OK)
                    .contentType("text/plain")
                    .header("Content-Disposition", "attachment; filename=\"example.txt\"")
                    .contentLength(fileBytes.length)
                    .body(fileBytes);
        }

        /**
         * Chunked流式响应示例
         */
        private void handleChunkedStream(HttpRequest request, HttpResponse response) throws Throwable {
            response.status(HttpStatus.OK)
                    .contentType("text/event-stream")
                    .setChunked(true); // 启用chunked编码

            // 发送多个数据块
            for (int i = 0; i < 5; ++i) {
                String data = "data: Message " + i + " at " + System.currentTimeMillis() + "\n\n";
                response.writeChunked(data.getBytes());

                // 模拟延迟
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            // 完成响应
            response.commit();
        }

        /**
         * 异步处理响应示例
         */
        private void handleAsyncProcessing(HttpRequest request, HttpResponse response) throws Throwable {
            // 关闭自动提交
            response.setAutoCommit(false);

            // 异步处理
            CompletableFuture.supplyAsync(new java.util.function.Supplier<String>() {
                public String get() {
                    // 模拟耗时操作
                    return processData();
                }
            }).thenAccept(new java.util.function.Consumer<String>() {
                public void accept(String result) {
                    try {
                        response.status(HttpStatus.OK)
                                .contentType("application/json")
                                .body(result.getBytes())
                                .commit();
                    } catch (Exception e) {
                        log.error("Async response error", e);
                    }
                }
            });
        }

        /**
         * SSE 简单推送示例（loop 模式）
         */
        private void handleSseSimple(HttpRequest request, HttpResponse response) throws Throwable {
            response.setHeader("Content-Type", "text/event-stream; charset=utf-8");
            response.setHeader("Cache-Control", "no-cache");
            response.setChunkedEncoding();
            for (int i = 0; i < 5; ++i) {
                response.sse("{\"count\":" + i + ",\"ts\":" + System.currentTimeMillis() + "}");
                Thread.sleep(1000);
            }
            response.commit();
        }

        /**
         * 默认处理
         */
        private void handleDefault(HttpRequest request, HttpResponse response) throws Throwable {
            String html = "<html><body>" +
                    "<h1>HTTP Response API Examples</h1>" +
                    "<ul>" +
                    "<li><a href='/basic'>Basic Response</a></li>" +
                    "<li><a href='/api/users'>JSON API</a></li>" +
                    "<li><a href='/download/example.txt'>File Download</a></li>" +
                    "<li><a href='/stream/events'>Chunked Stream</a></li>" +
                    "<li><a href='/async/process'>Async Processing</a></li>" +
                    "</ul>" +
                    "</body></html>";

            response.status(HttpStatus.OK)
                    .contentType("text/html;charset=utf-8")
                    .body(html.getBytes());
        }

        /**
         * 模拟数据处理
         */
        private String processData() {
            // 模拟耗时处理
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "{\"result\":\"processed\",\"timestamp\":" + System.currentTimeMillis() + "}";
        }
    }

    /**
     * 单独测试基础响应场景
     */
    public static void testBasicResponse() throws Exception {
        NioConfig config = new NioConfig();
        config.testMode();

        HTTPServer.of(8081, config)
                .requestHandler(new HttpRequestHandler() {
                    public void handle(HttpRequest request, HttpResponse response) throws Throwable {
                        response.status(HttpStatus.OK)
                                .contentType("text/plain;charset=utf-8")
                                .body("Basic response test".getBytes());
                    }
                })
                .start();

        log.info("Basic response server started on port 8081");
    }

    /**
     * 单独测试异步处理场景
     */
    public static void testAsyncResponse() throws Exception {
        NioConfig config = new NioConfig();
        config.testMode();

        HTTPServer.of(8082, config)
                .requestHandler(new HttpRequestHandler() {
                    public void handle(HttpRequest request, HttpResponse response) throws Throwable {
                        response.setAutoCommit(false);

                        CompletableFuture.runAsync(new Runnable() {
                            public void run() {
                                try {
                                    Thread.sleep(500); // 模拟处理时间
                                    response.status(HttpStatus.OK)
                                            .contentType("text/plain;charset=utf-8")
                                            .body("Async processing completed".getBytes())
                                            .commit();
                                } catch (Exception e) {
                                    log.error("Async processing failed", e);
                                }
                            }
                        });
                    }
                })
                .start();

        log.info("Async response server started on port 8082");
    }
}

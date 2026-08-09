package io.github.wycst.wastnet.examples.http;

import io.github.wycst.wast.log.Log;
import io.github.wycst.wast.log.LogFactory;
import io.github.wycst.wastnet.http.HTTPServer;
import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.http.HttpStatus;
import io.github.wycst.wastnet.http.handler.HttpRequestHandler;
import io.github.wycst.wastnet.socket.tcp.NioConfig;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * TCP直接发送Chunked请求测试
 * 绕过HttpClient API限制，直接构造HTTP chunked请求
 */
public class TcpChunkedRequestServerTest {

    private static final Log log = LogFactory.getLog(TcpChunkedRequestServerTest.class);

    public static void main(String[] args) throws Exception {
        // 启动测试服务器
        startTestServer();
        
//        // 等待服务器启动
//        Thread.sleep(1000);
//
//        // 发送chunked请求
//        sendChunkedRequest();
    }

    /**
     * 启动测试HTTP服务器
     */
    public static void startTestServer() throws Exception {
        NioConfig nioConfig = new NioConfig();
        nioConfig.testMode();
        nioConfig.setWorkerNum(4);

        HTTPServer.of(8080, nioConfig)
                .bufferSize(1024)
                .requestHandler(new ChunkedRequestHandler())
                .start();

        log.info("Test server started on port 8080");
        log.info("Ready to receive chunked requests");
    }

    /**
     * 直接通过TCP发送chunked请求
     */
    public static void sendChunkedRequest() {
        try {
            log.info("开始发送chunked请求...");
            
            Socket socket = new Socket("localhost", 8080);
            OutputStream outputStream = socket.getOutputStream();
            InputStream inputStream = socket.getInputStream();
            
            // 构造chunked请求头
            StringBuilder requestBuilder = new StringBuilder();
            requestBuilder.append("POST /upload-chunked HTTP/1.1\r\n");
            requestBuilder.append("Host: localhost:8080\r\n");
            requestBuilder.append("Content-Type: text/plain\r\n");
            requestBuilder.append("Transfer-Encoding: chunked\r\n");
            requestBuilder.append("Connection: keep-alive\r\n");
            requestBuilder.append("\r\n");
            
            String requestHeader = requestBuilder.toString();
            outputStream.write(requestHeader.getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
            
            log.info("已发送请求头:\n" + requestHeader);
            
            // 发送多个chunks
            String[] chunks = {
                "Hello Chunked World!",
                "This is the second chunk",
                "Third chunk with more data",
                "Final chunk content"
            };
            
            for (int i = 0; i < chunks.length; ++i) {
                String chunkData = chunks[i];
                String chunkHeader = Integer.toHexString(chunkData.getBytes().length) + "\r\n";
                
                // 发送chunk大小
                outputStream.write(chunkHeader.getBytes(StandardCharsets.UTF_8));
                log.info("发送chunk " + (i + 1) + " 大小: " + chunkHeader.trim() + " (" + chunkData.length() + ")");
                
                // 发送chunk数据
                outputStream.write(chunkData.getBytes(StandardCharsets.UTF_8));
                outputStream.write("\r\n".getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
                
                log.info("发送chunk " + (i + 1) + " 数据: " + chunkData);
                
                // 模拟延迟
                Thread.sleep(500);
            }
            
            // 发送结束标记
            outputStream.write("0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
            log.info("发送chunked结束标记: 0\\r\\n\\r\\n");
            
            // 读取响应
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
            StringBuilder response = new StringBuilder();
            String line;
            
            log.info("开始读取服务器响应...");
            while ((line = reader.readLine()) != null) {
                response.append(line).append("\n");
                if (line.isEmpty()) {
                    // 读取响应体
                    char[] buffer = new char[1024];
                    int len;
                    while ((len = reader.read(buffer)) != -1) {
                        response.append(buffer, 0, len);
                    }
                    break;
                }
            }
            
            log.info("收到服务器响应:\n" + response.toString());
            
            // 关闭连接
            socket.close();
            
        } catch (Exception e) {
            log.error("发送chunked请求失败", e);
        }
    }

    /**
     * 处理chunked请求的服务器端处理器
     */
    static class ChunkedRequestHandler implements HttpRequestHandler {
        
        @Override
        public void handle(HttpRequest request, HttpResponse response) throws Throwable {
            String uri = request.getRequestUri();
            log.info("收到请求: " + request.getMethod() + " " + uri);
            log.info("Content-Type: " + request.getContentType());
            log.info("Transfer-Encoding: " + request.getHeader("Transfer-Encoding"));
            log.info("Content-Length: " + request.getContentLength());
            
            if ("/upload-chunked".equals(uri)) {
                handleChunkedUpload(request, response);
            } else {
                response.status(HttpStatus.NOT_FOUND)
                        .contentType("text/plain;charset=utf-8")
                        .body("Endpoint not found".getBytes());
            }
        }
        
        /**
         * 处理chunked上传请求
         */
        private void handleChunkedUpload(HttpRequest request, HttpResponse response) throws Throwable {
            try {
                log.info("开始处理chunked上传请求...");
                
                // 读取请求体 - 框架已自动解码chunked格式
                InputStream bodyStream = request.bodyStream();
                if (bodyStream != null) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[1024];
                    int bytesRead;
                                
                    log.info("开始读取解码后的请求体数据...: " + bodyStream);
                    while ((bytesRead = bodyStream.read(buffer)) != -1) {
                        baos.write(buffer, 0, bytesRead);
                        log.info("读取到解码后数据块: " + bytesRead + " 字节");
                    }
                                
                    String receivedData = baos.toString(StandardCharsets.UTF_8.name());
                    log.info("完整接收的解码后数据:\n" + receivedData);
                                
                    // 验证数据完整性
                    String expectedData = "Hello Chunked World!This is the second chunkThird chunk with more dataFinal chunk content";
                    boolean dataIntegrity = expectedData.equals(receivedData);
                                
                    // 返回成功响应
                    String jsonResponse = "{\n" +
                            "  \"status\": \"success\",\n" +
                            "  \"message\": \"Chunked data received and decoded successfully\",\n" +
                            "  \"dataIntegrityVerified\": " + dataIntegrity + ",\n" +
                            "  \"receivedBytes\": " + receivedData.length() + ",\n" +
                            "  \"expectedBytes\": " + expectedData.length() + ",\n" +
                            "  \"dataPreview\": \"" + receivedData.substring(0, Math.min(100, receivedData.length())) + "...\"\n" +
                            "}";
                                
                    response.status(HttpStatus.OK)
                            .contentType("application/json;charset=utf-8")
                            .body(jsonResponse.getBytes(StandardCharsets.UTF_8));
                                        
                } else {
                    log.warn("请求体为空");
                    response.status(HttpStatus.BAD_REQUEST)
                            .contentType("application/json;charset=utf-8")
                            .body("{\"error\":\"Empty request body - chunked decoding may have failed\"}".getBytes());
                }
                
            } catch (Exception e) {
                log.error("处理chunked请求时发生错误", e);
                response.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType("application/json;charset=utf-8")
                        .body(("{\"error\":\"" + e.getMessage() + "\"}").getBytes());
            }
        }
    }
}

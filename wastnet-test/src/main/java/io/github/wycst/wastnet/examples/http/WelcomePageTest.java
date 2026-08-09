package io.github.wycst.wastnet.examples.http;

import io.github.wycst.wastnet.http.HTTPServer;

/**
 * 欢迎页面测试
 */
public class WelcomePageTest {

    public static void main(String[] args) throws Exception {
        // 创建HTTP服务器（使用默认的欢迎页面处理器）
        HTTPServer server = HTTPServer.of(8080)
                .start();
        
        System.out.println("Welcome page server started on port " + server.getPort());
        System.out.println("Visit: http://localhost:" + server.getPort());
        System.out.println("Press Ctrl+C to stop the server");
    }
}

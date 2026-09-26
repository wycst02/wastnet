package io.github.wycst.wastnet.examples.http;

import io.github.wycst.wast.log.Log;
import io.github.wycst.wast.log.LogFactory;
import io.github.wycst.wastnet.http.HTTPServer;
import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.http.handler.HttpRoute;
import io.github.wycst.wastnet.http.handler.HttpRouterHandler;

/**
 * SSE（Server-Sent Events）示例
 * <p>
 * 演示三种实现方式：
 * <ul>
 *   <li>原始实现：手动设 header、拼接 "data:" 格式、write/flush</li>
 *   <li>loop 模式：{@code response.sse()} 封装，handler 线程内循环推送</li>
 *   <li>emitter 模式：{@code router.sse()} + {@code emitter.emit()}，handler 在框架线程池中异步运行</li>
 * </ul>
 *
 * @author wangyc
 */
public class SseExample {

    private static final Log log = LogFactory.getLog(SseExample.class);

    public static void main(String[] args) throws Exception {
        HttpRouterHandler router = new HttpRouterHandler();

        // === 方式一：原始实现，展示 SSE 协议细节 ===
        // 手动设置 Content-Type/Cache-Control/chunked，自行拼接 data: 格式
        router.get("/sse/raw", new HttpRoute() {
            @Override
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.contentType("text/event-stream; charset=utf-8")
                        .header("Cache-Control", "no-cache")
                        .chunked();
                for (int i = 0; i < 5; ++i) {
                    String data = "data: {\"count\":" + i + "}\n\n";
                    response.write(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    response.flush();
                    Thread.sleep(1000);
                }
            }
        });

        // === 方式二：loop 模式，response.sse() 封装 ===
        // 一行 response.sse() 代替手动拼格式，自动处理 header/flush
        router.get("/sse/clock", new HttpRoute() {
            @Override
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                for (int i = 0; i < 10; ++i) {
                    response.sse("{\"tick\":" + i + ",\"time\":" + System.currentTimeMillis() + "}");
                    Thread.sleep(1000);
                }
            }
        });

        // === 方式三：emitter 模式，router.sse() + emitter.emit() ===
        // handler 在框架线程池中异步运行，不阻塞请求线程；60 秒无活动自动关闭
        router.sse("/sse/news", 60000L, emitter -> {
            for (int i = 1; i <= 10; ++i) {
                String id = "news-" + i;
                emitter.emit("news", "{\"id\":" + id + ",\"title\":\"Breaking News " + i + "\"}", id, 3000);
                Thread.sleep(1000);
            }
            emitter.close();
        });

        // === 方式三补充：emitter 模式同样能读取请求信息 ===
        // router.sse() 的 lambda 只拿到 emitter，但通过 emitter.request() 可访问
        // 底层 HttpRequest：查询参数 / header / uri 参数等，无需改用注解形式
        router.sse("/sse/feed", 60000L, emitter -> {
            HttpRequest req = emitter.request();
            String topic = req.getParameter("topic");       // 查询参数 ?topic=...
            String auth = req.getHeader("Authorization");   // 请求头
            for (int i = 1; i <= 5; ++i) {
                String id = "feed-" + i;
                String data = "{\"topic\":" + (topic == null ? "null" : "\"" + topic + "\"")
                        + ",\"seq\":" + i + ",\"authorized\":" + (auth != null) + "}";
                emitter.emit("feed", data, id, 3000);
                Thread.sleep(1000);
            }
            emitter.close();
        });

        // 启动服务器
        HTTPServer server = HTTPServer.of(8081)
                .requestHandler(router)
                .start();

        log.info("SSE server started on http://localhost:8081");
        log.info("  Raw (manual): http://localhost:8081/sse/raw");
        log.info("  Loop (sse()): http://localhost:8081/sse/clock");
        log.info("  Emitter:      http://localhost:8081/sse/news");
        log.info("  Emitter(req): http://localhost:8081/sse/feed?topic=sports  (Authorization header readable too)");
    }
}

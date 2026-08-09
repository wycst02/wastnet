package io.github.wycst.wastnet.examples.http;

import io.github.wycst.wast.log.Log;
import io.github.wycst.wast.log.LogFactory;
import io.github.wycst.wastnet.http.HTTPServer;
import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.http.handler.HttpResourceRoute;
import io.github.wycst.wastnet.http.handler.HttpRoute;
import io.github.wycst.wastnet.http.handler.HttpRouterHandler;
import io.github.wycst.wastnet.http.upgrade.websocket.WebSocketConnection;
import io.github.wycst.wastnet.http.upgrade.websocket.WebSocketFrame;
import io.github.wycst.wastnet.http.upgrade.websocket.WebSocketResource;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 综合演示：WebSocket 聊天室 + SSE 推送 + 静态页面
 * <p>
 * 访问 {@code http://localhost:8080/websocket-chat.html} 进入 WebSocket 聊天室
 * 访问 {@code http://localhost:8080/sse-demo.html} 进入 SSE 演示页
 */
public class ChatExample {

    private static final Log log = LogFactory.getLog(ChatExample.class);

    public static void main(String[] args) throws Exception {
        HttpRouterHandler router = new HttpRouterHandler();

        // 静态页面（pages 目录）
        String docBase = ChatExample.class.getResource("/pages").getPath();
        router.resource(new HttpResourceRoute("/", docBase));

        // WebSocket 聊天（通过 URL 参数 ?name=xxx 传递用户名）
        final AtomicInteger online = new AtomicInteger();
        router.ws("/ws/chat", new WebSocketResource(300) {

            @Override
            public boolean beforeHandshake(HttpRequest request, HttpResponse response) {
                return request.getParameter("name") != null;
            }

            @Override
            public void onOpen(WebSocketConnection conn) {
                String name = conn.request().getParameter("name");
                conn.setAccount(name);
                broadcastMessage(WebSocketFrame.textOf("system:" + name + " 加入了群聊（" + online.incrementAndGet() + "人）"));
                log.info("{} connected", name);
            }

            @Override
            public void onMessage(WebSocketConnection conn, String message) throws IOException {
                broadcastMessage(WebSocketFrame.textOf(conn.getAccount() + ":" + message));
            }

            @Override
            public void onClose(WebSocketConnection conn, int code, String reason) {
                broadcastMessage(WebSocketFrame.textOf("system:" + conn.getAccount() + " 离开了群聊（" + online.decrementAndGet() + "人）"));
                log.info("{} disconnected", conn.getAccount());
            }
        });

        // WebSocket 端到端编程式升级（embedded 模式）：
        // 通过 HttpRequest#upgrade(WebSocketResource) 在普通 HTTP 端点里手动完成升级，
        // 调用方直接持有 WebSocketConnection 并驱动数据交换。注意：
        //  - 编程式模式无群组能力，broadcast 必须禁用，因此用 new WebSocketResource(false)
        //  - 升级失败（Origin 校验不过 / 握手被 beforeHandshake 拒绝）时返回 null 且不写响应，
        //    由这里自行决定如何响应客户端
        router.get("/ws/upgrade", new HttpRoute() {
            @Override
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                WebSocketResource ws = new WebSocketResource(false) {

                    @Override
                    public boolean beforeHandshake(HttpRequest request, HttpResponse response) {
                        return request.getParameter("name") != null;
                    }

                    @Override
                    public void onOpen(WebSocketConnection conn) {
                        log.info("upgrade-open: id={} subprotocol={}", conn.id(), conn.subprotocol());
                        try {
                            conn.sendText("system: 连接已建立，echo 模式开启");
                        } catch (IOException e) {
                            log.error(e.getMessage(), e);
                        }
                    }

                    @Override
                    public void onMessage(WebSocketConnection conn, String message) throws IOException {
                        // 文本回声
                        conn.sendText("echo:" + message);
                    }

                    @Override
                    public void onBinary(WebSocketConnection conn, byte[] data) throws IOException {
                        // 二进制回声
                        conn.sendBinary(data);
                    }

                    @Override
                    public void onClose(WebSocketConnection conn, int code, String reason) {
                        log.info("upgrade-close: id={} code={} reason={}", conn.id(), code, reason);
                    }
                };

                WebSocketConnection conn = request.upgrade(ws);
                if (conn == null) {
                    // 升级未发生（Origin 校验失败或 beforeHandshake 返回 false），按普通 HTTP 响应
                    response.setContentType("text/plain");
                    response.write("upgrade rejected: missing ?name= or bad origin".getBytes());
                }
                // 升级成功后 handler 无需再写响应，后续帧事件由 ws 回调驱动
            }
        });

        // SSE 推送（loop 模式）
        router.get("/sse/clock", new HttpRoute() {
            @Override
            public void handle(String path, HttpRequest request,
                               HttpResponse response) throws Throwable {
                for (int i = 0; i < 10; ++i) {
                    response.sse("{\"tick\":" + i + ",\"time\":" + System.currentTimeMillis() + "}");
                    Thread.sleep(1000);
                }
            }
        });

        // SSE 推送（emitter 模式）
        router.sse("/sse/news", 60000L, emitter -> {
            for (int i = 1; i <= 10; ++i) {
                String id = "news-" + i;
                emitter.emit("news", "{\"id\":" + id + ",\"title\":\"Breaking News " + i + "\"}", id, 3000);
                Thread.sleep(1000);
            }
            emitter.close();
        });

        // 启动服务器
        HTTPServer.of(8080)
                .requestHandler(router)
                .startupBannerEnabled(false)
                .start();

        log.info("ChatExample server started on http://localhost:8080");
        log.info("  WebSocket Chat:      http://localhost:8080/websocket-chat.html");
        log.info("  WebSocket Upgrade:   http://localhost:8080/websocket-upgrade.html   (programmatic upgrade page)");
        log.info("  SSE Demo:            http://localhost:8080/sse-demo.html");
    }
}

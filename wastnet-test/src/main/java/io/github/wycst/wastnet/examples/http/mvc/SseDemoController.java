package io.github.wycst.wastnet.examples.http.mvc;

import io.github.wycst.wastnet.http.SseEmitter;
import io.github.wycst.wastnet.http.annotation.*;

import java.io.IOException;

/**
 * 服务端推送（@Sse）用法演示：方法返回 void 且包含恰好一个 SseEmitter 参数
 * （外加的 @RequestParam / @PathParam 等普通参数照常解析）。框架在流打开后
 * 注入 emitter，用 emit(...) 推送事件。
 * <p>
 * 关闭机制（框架有兜底，但仍推荐用完主动 close）：
 * 1) endpoint 方法返回 → runAsync 的 finally 自动 close()；
 * 2) 客户端断开 → ctx 关闭监听器立即释放（连接不会卡到超时）；
 * 3) 未配置 timeout 时默认 30 分钟全局超时作为绝对上限兜底。
 * 虽然不显式 close() 也有兜底，仍鼓励使用完主动调用 emitter.close()
 * 客户端用 EventSource 或 curl -N 消费，例如：
 * curl -N https://localhost:8080/sse/room/100
 */
@Controller
public class SseDemoController {

    // 不带参数：每秒推送一次，共 5 次后方法返回，框架自动关闭
    @Sse("/sse-clock")
    public void clock(SseEmitter emitter) throws IOException {
        for (int i = 1; i <= 5; i++) {
            emitter.emit("tick-" + i);
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    // 持续推送直到超时：timeout=10000ms 后框架强制断开连接（兜底）。
    @Sse(value = "/sse-stream", timeout = 10000)
    public void stream(SseEmitter emitter) throws IOException {
        long n = 0;
        while (true) {
            emitter.emit("tick-" + (++n));
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // 带路径参数：GET /sse/room/100 按房间号推送（原生 @PathParam + ${roomId} 模板）
    @Sse("/sse/room/${roomId}")
    public void room(@PathParam("roomId") long roomId, SseEmitter emitter) throws IOException {
        for (int i = 1; i <= 3; i++) {
            emitter.emit("room-" + roomId + "-msg-" + i);
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    // 带请求参数：GET /sse/user?id=123 按用户 id 推送一条后返回，框架自动关闭
    @Sse("/sse/user")
    public void user(@RequestParam("id") String id, SseEmitter emitter) throws IOException {
        emitter.emit("user-" + id + "-online");
    }

    // 全字段事件：event/data/id/retry 一次推送后返回，框架自动关闭
    @Sse("/sse-full")
    public void full(SseEmitter emitter) throws IOException {
        emitter.emit("greeting", "hello", "evt-1", 3000);
    }
}

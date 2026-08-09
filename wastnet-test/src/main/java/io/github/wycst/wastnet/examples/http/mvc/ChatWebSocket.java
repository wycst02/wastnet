package io.github.wycst.wastnet.examples.http.mvc;

import io.github.wycst.wastnet.http.annotation.WebSocket;
import io.github.wycst.wastnet.http.upgrade.websocket.WebSocketConnection;
import io.github.wycst.wastnet.http.upgrade.websocket.WebSocketResource;

/**
 * WebSocket 端点 — 演示 {@code @WebSocket}.
 *
 * @author wangyc
 */
@WebSocket("/ws/chat")
public class ChatWebSocket extends WebSocketResource {

    public ChatWebSocket() {
        super(30);
    }

    @Override
    public void onMessage(WebSocketConnection connection, String message) throws java.io.IOException {
        System.out.println("[ws] received: " + message);
        connection.sendText("echo: " + message);
    }

    @Override
    public void onOpen(WebSocketConnection connection) {
        System.out.println("[ws] client connected");
    }

    @Override
    public void onClose(WebSocketConnection connection, int code, String reason) {
        System.out.println("[ws] client disconnected (close frame)");
    }

    @Override
    public void onErrorClose(WebSocketConnection connection) {
        System.out.println("[ws] client disconnected (error/abnormal)");
    }
}

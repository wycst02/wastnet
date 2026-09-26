package io.github.wycst.wastnet.routerfixtures.wsextra;

import io.github.wycst.wastnet.http.annotation.WebSocket;
import io.github.wycst.wastnet.http.upgrade.websocket.WebSocketResource;

/**
 * Minimal {@code @WebSocket} endpoint used to drive coverage of
 * {@code DefaultUpgradeHandler.removeResources()} (the hot-reload path that drops only scanned
 * WebSocket endpoints). Must be a top-level class so the package scanner picks it up.
 */
@WebSocket("/ws/demo")
public class DemoWebSocket extends WebSocketResource {
}

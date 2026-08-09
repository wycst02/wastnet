package io.github.wycst.wastnet.http.upgrade;

import io.github.wycst.wastnet.http.upgrade.websocket.WebSocketConnection;
import io.github.wycst.wastnet.http.upgrade.websocket.WebSocketResource;

public class UpgradeWebSocketHolder implements UpgradeHolder {
    public final WebSocketResource resource;
    public final WebSocketConnection connection;

    public UpgradeWebSocketHolder(WebSocketResource resource, WebSocketConnection connection) {
        this.resource = resource;
        this.connection = connection;
    }

    @Override
    public boolean isWebSocket() {
        return true;
    }

    @Override
    public boolean isH2c() {
        return false;
    }

    @Override
    public UpgradeResource upgradeResource() {
        return resource;
    }
}

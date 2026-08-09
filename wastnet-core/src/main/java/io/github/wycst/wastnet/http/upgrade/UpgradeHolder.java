package io.github.wycst.wastnet.http.upgrade;

public interface UpgradeHolder {
    boolean isWebSocket();

    boolean isH2c();

    UpgradeResource upgradeResource();
}

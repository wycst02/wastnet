package io.github.wycst.wastnet.http.upgrade;

import io.github.wycst.wastnet.socket.tcp.ChannelContext;

/**
 * <p> http upgrade resource </p>
 *
 * @since 2024-1-27
 * @author wangyc
 */
public abstract class UpgradeResource {

    /**
     * Singleton h2c upgrade resource.
     */
    public static final UpgradeResource H2C = new UpgradeResource() {
        @Override
        public boolean isH2c() {
            return true;
        }
    };

    protected String path;

    public boolean isWebSocket() {
        return false;
    }

    public boolean isH2c() {
        return false;
    }

    protected final UpgradeResource path(String path) {
        this.path = path;
        return this;
    }

    public void handleOnClose(ChannelContext ctx) {
    }
}

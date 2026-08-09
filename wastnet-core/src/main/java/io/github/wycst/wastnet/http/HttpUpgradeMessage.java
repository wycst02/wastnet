package io.github.wycst.wastnet.http;

/**
 * Upgrade Message
 *
 * @since 2024-2-8
 * @author wangyc
 */
public interface HttpUpgradeMessage extends HttpMessage {

    boolean isWebSocket();

}

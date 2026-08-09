package io.github.wycst.wastnet.http;

/**
 * @since 2024-1-27
 * @author wangyc
 */
public interface HttpMessage {

    /**
     * Whether the message is a request
     *
     * @return true if the message is a request, false otherwise
     */
    boolean isHttpRequest();

    /**
     * Whether the message is an upgrade message
     *
     * @return true if the message is an upgrade message, false otherwise
     */
    boolean isUpgrade();
}

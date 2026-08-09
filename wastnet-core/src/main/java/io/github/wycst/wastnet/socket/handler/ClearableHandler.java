package io.github.wycst.wastnet.socket.handler;

/**
 * Handler with lifecycle management support.
 * Allows cleanup of resources when handler is cleared.
 *
 * @author wangyc
 */
public interface ClearableHandler {

    /**
     * Called when handler is cleared.
     */
    void clear();
}
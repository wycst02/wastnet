package io.github.wycst.wastnet.socket.tcp;

import javax.net.ssl.SSLContext;

/**
 * Factory interface for creating SSLContext instances.
 * Supports dynamic SSL context creation for scenarios like
 * certificate hot-reload and SNI-based context selection.
 */
public interface SSLContextFactory {

    /**
     * Create a new SSLContext instance
     *
     * @return the SSLContext
     */
    SSLContext create();
}

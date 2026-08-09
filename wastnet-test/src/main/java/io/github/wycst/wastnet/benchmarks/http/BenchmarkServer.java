package io.github.wycst.wastnet.benchmarks.http;

/**
 * Unified benchmark server contract so wastnet and undertow can be launched
 * with the same entry point and compared under identical pressure.
 */
public interface BenchmarkServer {

    /**
     * Start the server on the given port. Must block until the server is ready
     * to accept connections (caller may poll or just sleep after return).
     *
     * @param port listen port
     */
    void start(int port) throws Exception;

    /**
     * Stop the server and release resources.
     */
    void stop() throws Exception;
}

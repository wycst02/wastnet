package io.github.wycst.wastnet;

import io.github.wycst.wastnet.log.Log;
import io.github.wycst.wastnet.log.LogFactory;
import io.github.wycst.wastnet.socket.conf.SocketConf;
import org.junit.jupiter.api.Test;

import java.util.logging.Level;

/**
 * Coverage tests for small packages: socket.conf, log.
 */
class SmallPackCoverageTest {

    // ==================== SocketConf ====================

    @Test
    void testSocketConfGetProperty() {
        SocketConf.getProperty("wastnet.socket.test-key");
    }

    // ==================== Log ====================

    @Test
    void testLogLevels() {
        Log log = LogFactory.getLog(SmallPackCoverageTest.class);
        java.util.logging.Logger julLogger = java.util.logging.Logger.getLogger(
                "io.github.wycst.wastnet.SmallPackCoverageTest");
        julLogger.setLevel(Level.OFF);
        julLogger.setUseParentHandlers(false);
        log.error("test error {}", "arg");
        log.warn("test warn {}", "arg");
        log.info("test info {}", "arg");
        log.debug("test debug {}", "arg");
    }

    @Test
    void testLogCallerInfoEnabled() {
        Log log = LogFactory.getLog(SmallPackCoverageTest.class);
        java.util.logging.Logger julLogger = java.util.logging.Logger.getLogger(
                "io.github.wycst.wastnet.SmallPackCoverageTest");
        julLogger.setLevel(Level.OFF);
        julLogger.setUseParentHandlers(false);
        log.error("caller test");
    }
}

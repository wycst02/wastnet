package io.github.wycst.wastnet.log;

import io.github.wycst.wastnet.log.Log;
import io.github.wycst.wastnet.log.LogFactory;
import io.github.wycst.wastnet.log.LogFormatter;
import io.github.wycst.wastnet.log.LogImpl;
import io.github.wycst.wastnet.log.RotatingFileHandler;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link Log} / {@link LogImpl}.
 * Verifies the core routing rule: non-error output goes to access.log,
 * error output goes to error.log, and the enabled switch suppresses output.
 */
public class LogTest {

    private static RotatingFileHandler tempHandler(String prefix) throws Exception {
        File f = File.createTempFile(prefix, ".log");
        f.delete();
        return new RotatingFileHandler(f.getAbsolutePath(), 1024 * 1024, 2);
    }

    @Test
    public void testSetEnabledAndIsEnabled() {
        Log log = LogFactory.getLog(LogTest.class);
        log.setEnabled(true);
        assertTrue(log.isEnabled());
        log.setEnabled(false);
        assertFalse(log.isEnabled());
        log.setEnabled(true);
    }

    @Test
    public void testGetLogReturnsSameInstance() {
        Log log1 = LogFactory.getLog(String.class);
        Log log2 = LogFactory.getLog(String.class);
        assertSame(log1, log2);
    }

    @Test
    public void testNonErrorGoesToAccessLog() throws Exception {
        File accessFile = File.createTempFile("access", ".log");
        File errorFile = File.createTempFile("error", ".log");
        accessFile.delete();
        errorFile.delete();
        LogImpl log = new LogImpl("TestLogger",
                new RotatingFileHandler(accessFile.getAbsolutePath(), 1024 * 1024, 2),
                new RotatingFileHandler(errorFile.getAbsolutePath(), 1024 * 1024, 2));
        log.info("hello access");
        log.error("hello error");
        log.error("hello error 2", new RuntimeException("cause"));

        // Wait for the background batched flush (1s cadence) to commit the in-memory buffer to disk
        // before asserting on the files.
        Thread.sleep(1500);

        String accessContent = new String(Files.readAllBytes(accessFile.toPath()));
        String errorContent = new String(Files.readAllBytes(errorFile.toPath()));
        assertTrue(accessContent.contains("hello access"), "info should go to access.log");
        assertTrue(errorContent.contains("hello error"), "error should go to error.log");
        assertTrue(errorContent.contains("cause"), "throwable stack should be attached to error.log");
        assertFalse(accessContent.contains("hello error"), "error must not appear in access.log");

        accessFile.delete();
        errorFile.delete();
    }

    @Test
    public void testDisabledDoesNotWrite() throws Exception {
        File accessFile = File.createTempFile("access", ".log");
        accessFile.delete();
        LogImpl log = new LogImpl("TestLogger",
                new RotatingFileHandler(accessFile.getAbsolutePath(), 1024 * 1024, 2),
                tempHandler("error"));
        log.setEnabled(false);
        log.info("should not appear");
        log.error("should not appear either");
        assertFalse(accessFile.exists(), "disabled logger must not create the file");
        accessFile.delete();
    }

    @Test
    public void testFormatterRendersThrowable() {
        String line = new LogFormatter().format("ERROR", "TestLogger", System.currentTimeMillis(), "failed", null, new RuntimeException("boom"));
        assertTrue(line.contains("failed"));
        assertTrue(line.contains("boom"));
    }
}

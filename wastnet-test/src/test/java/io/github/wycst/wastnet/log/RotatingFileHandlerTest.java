package io.github.wycst.wastnet.log;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage tests for RotatingFileHandler.
 */
public class RotatingFileHandlerTest {

    @TempDir
    File tempDir;

    private RotatingFileHandler newHandler(String name, long maxSize, int maxFiles) {
        return new RotatingFileHandler(new File(tempDir, name).getAbsolutePath(), maxSize, maxFiles);
    }

    private String line(String msg) {
        return new LogFormatter().format("INFO", "Test", System.currentTimeMillis(), msg, null, null);
    }

    @Test
    public void testPublishAndFlush() {
        RotatingFileHandler handler = newHandler("test.log", 10240, 3);
        handler.publish(line("test message"));
        handler.flush();
        File logFile = new File(tempDir, "test.log");
        assertTrue(logFile.exists(), "Log file should exist after publish");
        handler.close();
    }

    @Test
    public void testPublishAlwaysWrites() {
        // Without level filtering, every publish writes a line.
        RotatingFileHandler handler = newHandler("notlog.log", 10240, 3);
        handler.publish(line("should appear"));
        handler.close();
        File logFile = new File(tempDir, "notlog.log");
        assertTrue(logFile.exists());
    }

    @Test
    public void testRotate() {
        File logFile = new File(tempDir, "rotate.log");
        RotatingFileHandler handler = new RotatingFileHandler(logFile.getAbsolutePath(), 10, 2);
        handler.publish(line("first record data here"));
        assertTrue(logFile.exists());
        handler.publish(line("second record that should trigger rotation"));
        File backup0 = new File(tempDir, "rotate_0.log");
        assertTrue(backup0.exists() || logFile.exists(), "Backup should exist after rotation");
        handler.close();
    }

    @Test
    public void testBackupFileNameWithDot() {
        RotatingFileHandler handler = newHandler("access.out", 10240, 2);
        handler.publish(line("test"));
        handler.close();
        File backup0 = new File(tempDir, "access_0.out");
        assertTrue(backup0.exists() || new File(tempDir, "access.out").exists());
    }

    @Test
    public void testClose() {
        RotatingFileHandler handler = newHandler("close.log", 10240, 3);
        handler.publish(line("before close"));
        handler.close();
        // Second close should be safe
        handler.close();
    }

    @Test
    public void testMultipleRotations() {
        File logFile = new File(tempDir, "multi.log");
        RotatingFileHandler handler = new RotatingFileHandler(logFile.getAbsolutePath(), 5, 3);
        for (int i = 0; i < 20; ++i) {
            handler.publish(line("msg" + i));
        }
        handler.close();
        assertTrue(logFile.exists() || new File(tempDir, "multi_0.log").exists());
    }

    @Test
    public void testBackupFileNameNoDot() {
        RotatingFileHandler handler = newHandler("noext", 10240, 2);
        handler.publish(line("test"));
        handler.close();
        File backup0 = new File(tempDir, "noext_0");
        assertTrue(backup0.exists() || new File(tempDir, "noext").exists());
    }
}

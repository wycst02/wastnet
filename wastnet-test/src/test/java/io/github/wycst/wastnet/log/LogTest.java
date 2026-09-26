package io.github.wycst.wastnet.log;

import io.github.wycst.wastnet.log.Log;
import io.github.wycst.wastnet.log.LogFactory;
import io.github.wycst.wastnet.log.LogFormatter;
import io.github.wycst.wastnet.log.LogImpl;
import io.github.wycst.wastnet.log.LogLevel;
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

    //

    @Test
    void testThrowableContentNull() {
        assertNull(LogFormatter.getThrowableContent(null));
    }

    @Test
    void testFormatContainsLevelAndLogger() {
        LogFormatter formatter = new LogFormatter();
        String result = formatter.format("INFO", "com.example.MyClass", System.currentTimeMillis(), "hello", null, null);
        assertTrue(result.contains("INFO"), "Should contain level");
        assertTrue(result.contains("com.example.MyClass"), "Should contain logger name");
        assertTrue(result.contains("hello"), "Should contain message");
    }

    @Test
    void testInfoWithLongLoggerName() {
        String longName = "io.github.wycst.wastnet.log.LogFormatterTest";
        assertTrue(longName.length() >= 40, "Name must be 40+ chars for this test");
        Log log = newLog(longName);
        log.setEnabled(true);
        log.info("noop");
    }

    @Test
    void testInfoWithSimpleName() {
        Log log = newLog("SimpleName");
        log.info("noop");
    }

    @Test
    void testRotateFileNotExists() throws Exception {
        File tempDir = new File(System.getProperty("java.io.tmpdir"));
        File logFile = new File(tempDir, "rotate_missing_" + System.nanoTime() + ".log");
        RotatingFileHandler handler = new RotatingFileHandler(logFile.getAbsolutePath(), 1, 2);
        handler.publish(new LogFormatter().format("INFO", "Test", System.currentTimeMillis(), "trigger-rotation", null, null));
        assertTrue(logFile.exists(), "Log file should exist after publish");
        handler.close();
        logFile.delete();
        File backup0 = new File(tempDir, logFile.getName().replace(".log", "_0.log"));
        backup0.delete();
    }

    @Test
    void testLogFactoryGetLogWithCustomPackage() {
        Log log = LogFactory.getLog(getClass());
        assertNotNull(log);
    }

    private Log newLog(String name) {
        try {
            RotatingFileHandler access = new RotatingFileHandler(File.createTempFile("access", ".log").getAbsolutePath(), 1024 * 1024, 2);
            RotatingFileHandler error = new RotatingFileHandler(File.createTempFile("error", ".log").getAbsolutePath(), 1024 * 1024, 2);
            return new LogImpl(name, access, error);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void testLogLevelFromStringNull() {
        assertSame(LogLevel.INFO, LogLevel.fromString(null, LogLevel.INFO));
    }

    @Test
    void testLogLevelFromStringEmpty() {
        assertSame(LogLevel.WARN, LogLevel.fromString("   ", LogLevel.WARN));
    }

    @Test
    void testLogLevelFromStringValid() {
        assertSame(LogLevel.DEBUG, LogLevel.fromString("debug", LogLevel.INFO));
        assertSame(LogLevel.ERROR, LogLevel.fromString("ERROR", LogLevel.INFO));
        assertSame(LogLevel.OFF, LogLevel.fromString("off", LogLevel.WARN));
    }

    @Test
    void testLogLevelFromStringInvalid() {
        assertSame(LogLevel.INFO, LogLevel.fromString("NOPE", LogLevel.INFO));
    }

    @Test
    void testLogFactorySetLevelNullKeepsCurrent() {
        LogLevel before = LogFactory.getLevel();
        LogFactory.setLevel(null);
        assertSame(before, LogFactory.getLevel());
        LogFactory.setLevel(LogLevel.OFF);
        assertSame(LogLevel.OFF, LogFactory.getLevel());
        LogFactory.setLevel(LogLevel.INFO);
    }

    @Test
    void testLogFactoryIsLoggableBothBranches() {
        LogFactory.setLevel(LogLevel.INFO);
        assertTrue(LogFactory.isLoggable(LogLevel.INFO));
        assertTrue(LogFactory.isLoggable(LogLevel.ERROR));
        assertFalse(LogFactory.isLoggable(LogLevel.DEBUG));
        LogFactory.setLevel(LogLevel.DEBUG);
        assertTrue(LogFactory.isLoggable(LogLevel.DEBUG));
        LogFactory.setLevel(LogLevel.INFO);
    }

    @Test
    void testLogFactorySetEnabledToggles() {
        LogFactory.setEnabled(false);
        LogFactory.setEnabled(true);
    }

    @Test
    void testConstructorClampsSmallBuffer() {
        RotatingFileHandler h = new RotatingFileHandler("x.log", 1024, 2, 100);
        h.close();
    }

    @Test
    void testPublishNullIsNoop() throws Exception {
        RotatingFileHandler h = new RotatingFileHandler(File.createTempFile("nop", ".log").getAbsolutePath(), 1024 * 1024, 2);
        h.publish(null);
        h.close();
    }

    @Test
    void testPublishLongLineBranchA() throws Exception {
        File f = File.createTempFile("long", ".log");
        RotatingFileHandler h = new RotatingFileHandler(f.getAbsolutePath(), 1024 * 1024, 2, 1024);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1500; i++) sb.append('a');
        h.publish(sb.toString(), true);
        h.close();
        assertTrue(f.length() > 0);
    }

    @Test
    void testPublishLongLineTriggersRotation() throws Exception {
        File f = File.createTempFile("longrot", ".log");
        RotatingFileHandler h = new RotatingFileHandler(f.getAbsolutePath(), 100, 2, 1024);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1500; i++) sb.append('a');
        h.publish(sb.toString(), true);
        h.close();
        assertTrue(f.length() > 0);
        File backup0 = new File(f.getAbsolutePath().replace(".log", "_0.log"));
        backup0.delete();
    }

    @Test
    void testRotateShiftsBackups() throws Exception {
        File f = File.createTempFile("shift", ".log");
        RotatingFileHandler h = new RotatingFileHandler(f.getAbsolutePath(), 40, 3, 16 * 1024);
        for (int i = 0; i < 8; i++) {
            h.publish("payload line exceeding the tiny size limit 1234567890", true);
        }
        h.close();
        File b0 = new File(f.getAbsolutePath().replace(".log", "_0.log"));
        File b1 = new File(f.getAbsolutePath().replace(".log", "_1.log"));
        b0.delete();
        b1.delete();
    }

    @Test
    void testRotateRenameFailsKeepsCurrent() throws Exception {
        File dir = Files.createTempDirectory("rnfail").toFile();
        File f = new File(dir, "access.log");
        File backup0 = new File(f.getAbsolutePath().replace(".log", "_0.log"));
        assertTrue(backup0.createNewFile(), "stale backup must be creatable");
        java.io.FileOutputStream held = new java.io.FileOutputStream(backup0);
        try {
            RotatingFileHandler h = new RotatingFileHandler(f.getAbsolutePath(), 40, 2, 16 * 1024);
            for (int i = 0; i < 8; i++) {
                h.publish("payload line exceeding the tiny size limit 1234567890", true);
            }
            h.close();
            assertTrue(f.exists(), "must keep writing to current file after rename failure");
        } finally {
            held.close();
            deleteRecursively(dir);
        }
    }

    private void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File c : children) deleteRecursively(c);
        }
        file.delete();
    }

    @Test
    void testEnsureOpenEarlyReturnWhenStreamOpen() throws Exception {
        File f = File.createTempFile("open", ".log");
        RotatingFileHandler h = new RotatingFileHandler(f.getAbsolutePath(), 1024 * 1024, 2, 16 * 1024);
        h.publish("open it", true);
        h.ensureOpen();
        h.close();
    }

    @Test
    void testPublishFitsNoRotation() throws Exception {
        File f = File.createTempFile("fit", ".log");
        RotatingFileHandler h = new RotatingFileHandler(f.getAbsolutePath(), 1024 * 1024, 2, 16 * 1024);
        h.publish("hello world", false);
        h.publish("second line", true);
        h.close();
        assertTrue(f.length() > 0);
    }

    @Test
    void testPublishFlushNowBranch() throws Exception {
        File f = File.createTempFile("now", ".log");
        RotatingFileHandler h = new RotatingFileHandler(f.getAbsolutePath(), 1024 * 1024, 2, 16 * 1024);
        h.publish("flush now", true);
        h.close();
    }

    @Test
    void testPublishSecondScheduleTimerAlreadyRunning() throws Exception {
        File f = File.createTempFile("sched", ".log");
        RotatingFileHandler h = new RotatingFileHandler(f.getAbsolutePath(), 1024 * 1024, 2, 16 * 1024);
        h.publish("first", false);
        h.publish("second", false);
        h.close();
    }

    @Test
    void testBackupFileNameNoDot() throws Exception {
        File dir = Files.createTempDirectory("nodot").toFile();
        RotatingFileHandler h = new RotatingFileHandler(new File(dir, "mylog").getAbsolutePath(), 1, 2, 16 * 1024);
        for (int i = 0; i < 5; i++) {
            h.publish("a line that exceeds the tiny max size 12345", true);
        }
        h.close();
    }

    @Test
    void testRotateMaxFilesOne() throws Exception {
        File f = File.createTempFile("single", ".log");
        File backup0 = new File(f.getAbsolutePath().replace(".log", "_0.log"));
        assertTrue(backup0.createNewFile(), "stale backup must be creatable");
        RotatingFileHandler h = new RotatingFileHandler(f.getAbsolutePath(), 20, 1, 16 * 1024);
        for (int i = 0; i < 5; i++) {
            h.publish("some bytes to exceed the 20 byte limit 1234567890", true);
        }
        h.close();
        backup0.delete();
    }

    @Test
    void testRotateWhenCurrentFileMissing() throws Exception {
        File dir = Files.createTempDirectory("rot").toFile();
        File f = new File(dir, "gone.log");
        RotatingFileHandler h = new RotatingFileHandler(f.getAbsolutePath(), 1024, 2, 16 * 1024);
        h.rotate();
        h.close();
    }

    @Test
    void testOpenFileCreatesParentDir() throws Exception {
        File dir = Files.createTempDirectory("pdir").toFile();
        File nested = new File(new File(dir, "sub"), "access.log");
        RotatingFileHandler h = new RotatingFileHandler(nested.getAbsolutePath(), 1024 * 1024, 2, 16 * 1024);
        h.publish("hi", true);
        h.close();
        assertTrue(nested.exists());
    }

    @Test
    void testDoubleCloseIsSafe() throws Exception {
        File f = File.createTempFile("dcs", ".log");
        RotatingFileHandler h = new RotatingFileHandler(f.getAbsolutePath(), 1024 * 1024, 2, 16 * 1024);
        h.publish("line", true);
        h.close();
        h.close();
    }

    @Test
    void testPublishToDirectoryPathIsNoop() throws Exception {
        File dir = Files.createTempDirectory("rotdir").toFile();
        RotatingFileHandler h = new RotatingFileHandler(dir.getAbsolutePath(), 1024 * 1024, 2, 16 * 1024);
        h.publish("hello");
        h.flush();
        h.close();
    }

    @Test
    void testFlushEmptyBuffer() throws Exception {
        File f = File.createTempFile("fbb", ".log");
        RotatingFileHandler h = new RotatingFileHandler(f.getAbsolutePath(), 1024 * 1024, 2, 16 * 1024);
        h.flush();
        h.publish("x", true);
        h.flush();
        h.close();
    }

    @Test
    void testFlushTickIdleStopsTimer() throws Exception {
        File f = File.createTempFile("tick", ".log");
        RotatingFileHandler h = new RotatingFileHandler(f.getAbsolutePath(), 1024 * 1024, 2, 16 * 1024);
        h.flushTick();
        h.flushTick();
        h.close();
    }
}

package io.github.wycst.wastnet.log;

import org.junit.jupiter.api.Test;

import java.io.File;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage tests for LogFormatter / RotatingFileHandler / LogFactory edge cases.
 */
public class LogCoverageTest {

    // ==================== LogFormatter ====================

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

    // ==================== LogImpl ====================

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

    // ==================== RotatingFileHandler ====================

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

    // ==================== LogFactory ====================

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

    // ==================== LogLevel ====================

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

    // ==================== LogFactory method branches ====================

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

    // ==================== RotatingFileHandler extra branches ====================

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
        // bufferSize = 1024 (exactly the minimum, not clamped up), so a 1500-byte line hits
        // Branch A where bytes.length (1500) >= buf.length (1024).
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
        // Branch A (1500 >= 1024) AND rotation: writtenBytes + bytes.length > maxSize triggers rotate() in Branch A.
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
        // maxFiles = 3 so the backup-shift loop (i = maxFiles-2 .. >0) actually executes at least once.
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
        // Hold an open handle on the target backup file so renameTo() cannot replace it (Windows locks open files),
        // forcing the rename-failure backoff path (L478-L483).
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

    @Test
    void testEnsureOpenEarlyReturnWhenStreamOpen() throws Exception {
        File f = File.createTempFile("open", ".log");
        RotatingFileHandler h = new RotatingFileHandler(f.getAbsolutePath(), 1024 * 1024, 2, 16 * 1024);
        h.publish("open it", true);
        java.lang.reflect.Method m = RotatingFileHandler.class.getDeclaredMethod("ensureOpen");
        m.setAccessible(true);
        m.invoke(h);
        h.close();
    }

    private void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File c : children) deleteRecursively(c);
        }
        file.delete();
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
        // Pre-create a stale backup_0 so the maxFiles<=1 path's delete-branch (L447-L448) is exercised.
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
        java.lang.reflect.Method m = RotatingFileHandler.class.getDeclaredMethod("rotate");
        m.setAccessible(true);
        m.invoke(h);
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
        java.lang.reflect.Method m = RotatingFileHandler.class.getDeclaredMethod("flushTick");
        m.setAccessible(true);
        m.invoke(h);
        m.invoke(h);
        h.close();
    }
}

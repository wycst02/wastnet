/*
 * Copyright 2026, wangyunchao.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.wycst.wastnet.log;

import io.github.wycst.wastnet.util.Utils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rotating file output handler.
 * <p>
 * Naming convention (example below uses maxFiles=2; any value &gt;= 2 is supported):
 * <ul>
 *   <li>Current file: access.log</li>
 *   <li>Backup files: access_0.log, access_1.log, ... (index ascending, newest is _0)</li>
 * </ul>
 * Rotation is triggered when the current file size exceeds maxSize:
 * <ol>
 *   <li>Delete the oldest backup (e.g. access_0.log when maxFiles=2, or access_&lt;maxFiles-2&gt;.log in general)</li>
 *   <li>Rename access.log to access_0.log (shifting existing backups up by one index)</li>
 *   <li>Create a new access.log and continue writing</li>
 * </ol>
 * At most maxFiles files are retained on disk (current file included).
 * <p>
 * <b>Buffering:</b> {@link #publish(String)} copies the formatted line into an in-memory buffer
 * (a plain {@code arraycopy}, zero syscalls) and schedules a batched flush via a lazy background
 * timer instead of flushing on every call. The timer commits the buffer at a fixed 1-second
 * cadence while data keeps arriving, and stops itself (no idle spinning, and releases its worker
 * thread + shutdown hook) once idle for {@link #IDLE_LIMIT} consecutive ticks &mdash; so the
 * buffer is flushed at most {@link #IDLE_LIMIT}+1 seconds after the last write, without any
 * per-publish flush syscall or idle CPU, and an un-closed handler that stops receiving logs is
 * fully reclaimable by the GC. The scheduler and shutdown hook are created lazily on first
 * activity and torn down on idle/close. The actual disk write is performed by {@link #flush()},
 * which is CAS-guarded: concurrent publishes (and the timer) coalesce into a single flush.
 * The buffer is always committed to the correct (old) file before any rotation, so pending data
 * is never lost. A JVM shutdown hook flushes any remaining buffered data on exit.
 *
 * @since 2025-6-15
 * @author wangyc
 */
class RotatingFileHandler {

    private static final Charset UTF_8 = Utils.UTF_8;

    private final String baseFilePath;
    private final long maxSize;
    private final int maxFiles;

    // In-memory write buffer: publish() only copies data here (pure memory, zero syscalls),
    // the actual disk write is performed by flush().
    private final byte[] buf;
    private int bufCount = 0;

    private FileOutputStream outputStream;
    private long writtenBytes;

    // CAS guard for flush(): concurrent callers (concurrent publishes / JVM shutdown hook /
    // manual call) never flush redundantly.
    private final AtomicBoolean flushing = new AtomicBoolean(false);

    // Lazy 1s flush timer: drains the buffer while data keeps arriving, then stops after
    // IDLE_LIMIT idle ticks (releasing the worker thread + shutdown hook, so idle handler is GC-reclaimable).
    private static final int IDLE_LIMIT = 2;
    private static final long FLUSH_INTERVAL_SECONDS = 1;
    private ScheduledExecutorService scheduler;
    private final Runnable flushTask = this::flushTick;
    private ScheduledFuture<?> timerFuture;
    private volatile boolean timerRunning = false;
    private final Object timerLock = new Object();
    private final AtomicLong pending = new AtomicLong(0);
    private Thread shutdownHookThread;
    private int idleTicks = 0;

    // Backoff on renameTo failures: a locked file (e.g. a Windows log viewer / antivirus) makes
    // rotation fail persistently, so we stop retrying on every publish beyond a threshold.
    private int renameFailStreak = 0;
    private static final int RENAME_FAIL_MAX = 3;
    private static final int RENAME_BACKOFF_PUBLISHES = 60;

    /**
     * @param baseFilePath file path, e.g. {@code logs/access.log}
     * @param maxSize      max bytes per file
     * @param maxFiles     max number of files to retain (including the current one)
     */
    public RotatingFileHandler(String baseFilePath, long maxSize, int maxFiles) {
        this(baseFilePath, maxSize, maxFiles, 16 * 1024);
    }

    /**
     * @param baseFilePath file path, e.g. {@code logs/access.log}
     * @param maxSize      max bytes per file
     * @param maxFiles     max number of files to retain (including the current one)
     * @param bufferSize   in-memory write buffer size in bytes (used to batch disk writes)
     */
    public RotatingFileHandler(String baseFilePath, long maxSize, int maxFiles, int bufferSize) {
        this.baseFilePath = baseFilePath;
        this.maxSize = maxSize;
        this.maxFiles = maxFiles;
        int size = Math.max(bufferSize, 1024);
        this.buf = new byte[size];
    }

    private void ensureOpen() throws IOException {
        if (outputStream != null) {
            return;
        }
        openFile(true);
    }

    private void openFile(boolean append) throws IOException {
        File file = new File(baseFilePath);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        writtenBytes = append && file.exists() ? file.length() : 0;
        outputStream = new FileOutputStream(file, append);
    }

    /**
     * Close the underlying stream and release the OS handle, but keep the scheduler and shutdown
     * hook alive. Used by rotation, which must release the handle before renaming the file on
     * Windows yet must not tear down the background flush machinery.
     */
    private void closeStreamOnly() {
        if (outputStream != null) {
            try {
                outputStream.close();
            } catch (IOException e) {
                System.err.println("Close failed: " + baseFilePath + " (" + e.getMessage() + ")");
            }
            outputStream = null;
        }
    }

    /**
     * Write a single, already-formatted log line into the in-memory buffer (a plain
     * {@code arraycopy}, zero syscalls) and then trigger {@link #flush()}. Because
     * {@link #flush()} is CAS-guarded, concurrent publishes coalesce into a single actual disk
     * write: every info/error still flushes, but under contention only one flush syscall occurs.
     */
    public void publish(String line) {
        publish(line, false);
    }

    /**
     * Overload of {@link #publish(String)} with an explicit flush control.
     *
     * @param flushNow when {@code true}, the in-memory buffer is committed to disk synchronously
     *                 (via {@link #flush()}) before returning, so the line is durable immediately;
     *                 when {@code false}, the write is batched and committed later by the
     *                 background timer. Real-time flushing is intended for error output where
     *                 losing the last line on a crash is unacceptable.
     */
    public void publish(String line, boolean flushNow) {
        if (line == null) {
            return;
        }
        // Write into the in-memory buffer (synchronized for thread safety; before rotation the
        // buffer is committed to the current/old stream first).
        byte[] bytes = line.getBytes(UTF_8);
        synchronized (this) {
            if (outputStream == null) {
                try {
                    ensureOpen();
                } catch (IOException e) {
                    System.err.println("Failed to open log file: " + baseFilePath + " (" + e.getMessage() + ")");
                    return;
                }
            }

            // Branch A: a line >= buffer size cannot fit in buf, so commit buf to the old stream
            // then write the over-long line directly (rotate first if needed); uses an immediate flush().
            if (bytes.length >= buf.length) {
                try {
                    flushBufToStream();
                } catch (IOException e) {
                    System.err.println("Write failed: " + baseFilePath + " (" + e.getMessage() + ")");
                }
                if (maxSize > 0 && writtenBytes + bytes.length > maxSize && shouldAttemptRotate()) {
                    try {
                        rotate();
                    } catch (IOException e) {
                        System.err.println("Rotation failed: " + baseFilePath + " (" + e.getMessage() + ")");
                        return;
                    }
                }
                try {
                    outputStream.write(bytes);
                    writtenBytes += bytes.length;
                } catch (IOException e) {
                    // Best-effort: drop the held handle and retry once on a fresh stream. If it
                    // still fails, the line is dropped but the stream/buffer stay consistent.
                    System.err.println("Write failed, retrying: " + baseFilePath + " (" + e.getMessage() + ")");
                    try {
                        closeStreamOnly();
                        ensureOpen();
                        outputStream.write(bytes);
                        writtenBytes += bytes.length;
                    } catch (IOException e2) {
                        System.err.println("Write failed permanently, dropping line: " + baseFilePath + " (" + e2.getMessage() + ")");
                    }
                }
                flush();
            } else {
                // Branch B: a normal line, try to append to buf first.
                if (bufCount + bytes.length > buf.length) {
                    try {
                        flushBufToStream();
                    } catch (IOException e) {
                        System.err.println("Write failed: " + baseFilePath + " (" + e.getMessage() + ")");
                    }
                }
                // Before rotation, commit buf to the old stream first, otherwise the pending
                // data would be written into the renamed old file or lost.
                if (maxSize > 0 && writtenBytes + bufCount + bytes.length > maxSize) {
                    try {
                        flushBufToStream();
                    } catch (IOException e) {
                        System.err.println("Write failed: " + baseFilePath + " (" + e.getMessage() + ")");
                    }
                    if (shouldAttemptRotate()) {
                        try {
                            rotate();
                        } catch (IOException e) {
                            System.err.println("Rotation failed: " + baseFilePath + " (" + e.getMessage() + ")");
                            return;
                        }
                    }
                }
                System.arraycopy(bytes, 0, buf, bufCount, bytes.length);
                bufCount += bytes.length;

                // Schedule a batched flush via the lazy timer instead of flushing per publish.
                if (flushNow) {
                    flush();
                } else {
                    scheduleFlush();
                }
            }
        }
    }

    /**
     * Mark that new data is pending and lazily start the background flush timer. The timer
     * commits the in-memory buffer at a fixed 1-second cadence while data keeps arriving, and
     * stops itself (no idle spinning, releasing its worker thread + shutdown hook) once idle for
     * {@link #IDLE_LIMIT} consecutive ticks. The scheduler and shutdown hook are created lazily
     * on first activity and torn down on idle/close.
     */
    private void scheduleFlush() {
        pending.incrementAndGet();
        boolean start;
        synchronized (timerLock) {
            start = !timerRunning;
            if (start) {
                timerRunning = true;
                idleTicks = 0;
                ensureScheduler();
                ensureShutdownHook();
                timerFuture = scheduler.scheduleAtFixedRate(flushTask, FLUSH_INTERVAL_SECONDS,
                        FLUSH_INTERVAL_SECONDS, TimeUnit.SECONDS);
            }
        }
    }

    private void ensureScheduler() {
        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "RotatingFileHandler-" + baseFilePath);
                t.setDaemon(true);
                return t;
            });
        }
    }

    private void ensureShutdownHook() {
        if (shutdownHookThread == null) {
            shutdownHookThread = new Thread(this::onJvmShutdown, "RotatingFileHandler-shutdown-" + baseFilePath);
            Runtime.getRuntime().addShutdownHook(shutdownHookThread);
        }
    }

    private void removeShutdownHook() {
        if (shutdownHookThread != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHookThread);
            } catch (IllegalStateException e) {
                // JVM is already shutting down; the hook is being run, ignore.
            }
            shutdownHookThread = null;
        }
    }

    /**
     * Background flush tick: commit pending data if any arrived since the last tick, otherwise
     * stop the timer (idle). The whole body is guarded by try/catch because a thrown exception
     * from a {@code ScheduledExecutorService} task would silently cancel all future executions.
     */
    private void flushTick() {
        try {
            if (pending.getAndSet(0) > 0) {
                flush();
                synchronized (timerLock) {
                    idleTicks = 0;
                }
            } else {
                boolean stop;
                synchronized (timerLock) {
                    stop = ++idleTicks >= IDLE_LIMIT;
                }
                if (stop) {
                    stopTimer();
                }
            }
        } catch (Throwable t) {
            System.err.println("Log flush task failed: " + baseFilePath + " (" + t.getMessage() + ")");
        }
    }

    private void stopTimer() {
        ScheduledFuture<?> f;
        ScheduledExecutorService s;
        synchronized (timerLock) {
            if (!timerRunning) {
                return;
            }
            timerRunning = false;
            idleTicks = 0;
            f = timerFuture;
            timerFuture = null;
            s = scheduler;
        }
        if (f != null) {
            f.cancel(false);
        }
        // Best-effort flush of any buffer left behind by a previously failed flush, so un-closed
        // handlers that stop receiving logs do not silently lose pending data on idle shutdown.
        flush();
        // Terminate the worker thread so it no longer acts as a GC root pinning this handler.
        if (s != null && !s.isShutdown()) {
            s.shutdown();
        }
        removeShutdownHook();
    }

    private void shutdownTimer() {
        ScheduledFuture<?> f;
        ScheduledExecutorService s;
        synchronized (timerLock) {
            if (timerRunning) {
                timerRunning = false;
                idleTicks = 0;
                f = timerFuture;
                timerFuture = null;
            } else {
                f = null;
            }
            s = scheduler;
        }
        if (f != null) {
            f.cancel(false);
        }
        if (s != null && !s.isShutdown()) {
            s.shutdownNow();
        }
    }

    private void onJvmShutdown() {
        // Owns its own shutdown hook (registered lazily); delegate to close() on JVM exit to keep
        // a single close path and avoid a redundant hook from LogFactory.
        close();
    }

    private String backupFileName(int index) {
        int dot = baseFilePath.lastIndexOf('.');
        if (dot > baseFilePath.lastIndexOf(File.separatorChar)) {
            return baseFilePath.substring(0, dot) + "_" + index + baseFilePath.substring(dot);
        }
        return baseFilePath + "_" + index;
    }

    /**
     * Whether a rotation should be attempted on this publish. After {@link #RENAME_FAIL_MAX}
     * consecutive rename failures we suspend automatic rotation and only probe every
     * {@link #RENAME_BACKOFF_PUBLISHES} publishes, so a persistently locked file stops flooding
     * stderr and no longer triggers a useless rename attempt on (almost) every publish.
     */
    private boolean shouldAttemptRotate() {
        if (renameFailStreak < RENAME_FAIL_MAX) {
            return true;
        }
        return (renameFailStreak % RENAME_BACKOFF_PUBLISHES) == 0;
    }

    /**
     * Perform file rotation:
     * <ol>
     *   <li>Delete the oldest backup {@code basePath_<i>n</i>.log}</li>
     *   <li>Shift {@code _i.log} to {@code _i+1.log} sequentially</li>
     *   <li>Rename the current file to {@code _0.log}</li>
     *   <li>Create a new current file</li>
     * </ol>
     */
    private void rotate() throws IOException {
        // Flush + release the OS handle so the file can be renamed (a held handle makes renameTo
        // fail silently on Windows); do NOT close() or the scheduler/shutdown hook would be torn down.
        flushBufToStream();
        closeStreamOnly();

        if (maxFiles <= 1) {
            // Retain only the current file: no backups, so just truncate the current file.
            // Also drop any stale backup left over from a previous (larger) maxFiles setting.
            File backup0 = new File(backupFileName(0));
            if (backup0.exists()) {
                backup0.delete();
            }
            openFile(false);
            bufCount = 0;
            return;
        }

        File currentFile = new File(baseFilePath);
        if (!currentFile.exists()) {
            openFile(false);
            bufCount = 0;
            return;
        }

        // delete the oldest backup
        File oldest = new File(backupFileName(maxFiles - 2));
        if (oldest.exists()) {
            oldest.delete();
        }

        // shift remaining backups
        for (int i = maxFiles - 2; i > 0; i--) {
            File src = new File(backupFileName(i - 1));
            if (src.exists()) {
                src.renameTo(new File(backupFileName(i)));
            }
        }

        // Rename current to _0; on Windows renameTo returns false (not throws) when the target is
        // locked/leftover/cross-volume, so keep appending rather than truncating the valid file.
        if (!currentFile.renameTo(new File(backupFileName(0)))) {
            ++renameFailStreak;
            System.err.println("Rotate rename failed (streak=" + renameFailStreak
                    + "), keep writing to current file: " + baseFilePath);
            openFile(true);
            return;
        }
        // Rotation succeeded: clear the consecutive-failure counter so automatic rotation
        // resumes immediately instead of staying in the backoff window.
        renameFailStreak = 0;
        openFile(false);
        bufCount = 0;
    }

    /**
     * Commit the in-memory buffer to the output stream. Must be called while holding the lock.
     */
    private void flushBufToStream() throws IOException {
        if (bufCount > 0 && outputStream != null) {
            outputStream.write(buf, 0, bufCount);
            writtenBytes += bufCount;
            bufCount = 0;
        }
    }

    /**
     * Flush the in-memory buffer to disk. Uses a CAS guard so that concurrent callers (from
     * concurrent publishes, the JVM shutdown hook, or a manual call) never flush redundantly.
     */
    public void flush() {
        if (!flushing.compareAndSet(false, true)) {
            return;
        }
        try {
            synchronized (this) {
                try {
                    flushBufToStream();
                } catch (IOException e) {
                    System.err.println("Flush failed: " + baseFilePath + " (" + e.getMessage() + ")");
                }
                if (outputStream != null) {
                    try {
                        outputStream.flush();
                    } catch (IOException e) {
                        System.err.println("Flush failed: " + baseFilePath + " (" + e.getMessage() + ")");
                    }
                }
            }
        } finally {
            flushing.set(false);
        }
    }

    public synchronized void close() {
        // Detach the shutdown hook and stop the timer first so no concurrent flush writes to the
        // stream while closing, and the handler becomes fully reclaimable.
        removeShutdownHook();
        shutdownTimer();
        try {
            flushBufToStream();
        } catch (IOException e) {
            System.err.println("Flush failed: " + baseFilePath + " (" + e.getMessage() + ")");
        }
        try {
            if (outputStream != null) {
                outputStream.close();
                outputStream = null;
            }
        } catch (IOException e) {
            System.err.println("Close failed: " + baseFilePath + " (" + e.getMessage() + ")");
        }
    }
}

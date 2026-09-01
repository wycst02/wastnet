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

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Log factory providing the internal {@link Log} instance via {@link #getLog(Class)}.
 * <p>
 * A logger obtained this way routes all non-error level output (debug/info/warn)
 * to {@code access.log} and error output to {@code error.log}. Log directory is
 * configurable via the system property {@code wastnet.log.dir}, defaulting to
 * {@code logs} (relative to the startup directory). The in-memory write buffer
 * size is configurable via {@code wastnet.log.bufferSize} (default 16KB, clamped
 * to [16KB, 1MB]); logging can be turned off entirely via
 * {@code -Dwastnet.log.disabled=true}. The per-file rotation size is configurable via
 * {@code wastnet.log.maxSize} (default 20MB, clamped to [1MB, 1GB]).
 * <p>
 * This factory does not depend on {@code java.util.logging}, so JUL's {@code LogManager}
 * (and its JMX platform-MBean registration) is never pulled into the startup path.
 *
 * @since 2025-6-15
 * @author wangyc
 */
public final class LogFactory {

    private static final Map<Class<?>, Log> LOGS = new ConcurrentHashMap<>();
    private static final String LOG_DIR;

    private static final RotatingFileHandler ACCESS_HANDLER;
    private static final RotatingFileHandler ERROR_HANDLER;

    private static volatile boolean enabled;

    private static volatile LogLevel globalLevel;

    static {
        // log directory
        String dir = System.getProperty("wastnet.log.dir", "");
        LOG_DIR = dir.isEmpty() ? "logs" : dir;

        // in-memory write buffer size; only resolved when logging is enabled
        int bufferSize = 0;

        // global on/off switch: default on; -Dwastnet.log.disabled=true disables all logging
        enabled = !Boolean.getBoolean("wastnet.log.disabled");
        if (enabled) {
            bufferSize = Integer.getInteger("wastnet.log.bufferSize", 16 * 1024);
            if (bufferSize < 16 * 1024) {
                bufferSize = 16 * 1024;
            } else if (bufferSize > 1024 * 1024) {
                bufferSize = 1024 * 1024;
            }
        }

        // global log level: default INFO; -Dwastnet.log.level=DEBUG|INFO|WARN|ERROR|OFF overrides
        globalLevel = LogLevel.fromString(System.getProperty("wastnet.log.level"), LogLevel.INFO);

        // per-file rotation threshold: default 20MB, clamped to [1MB, 1GB]
        long maxSize = Long.getLong("wastnet.log.maxSize", 20L * 1024 * 1024);
        if (maxSize < 1024 * 1024) {
            maxSize = 1024 * 1024;
        } else if (maxSize > 1024L * 1024 * 1024) {
            maxSize = 1024L * 1024 * 1024;
        }

        // number of retained files (current + backups): default and minimum 2
        int maxFiles = Integer.getInteger("wastnet.log.maxFiles", 2);
        if (maxFiles < 2) {
            maxFiles = 2;
        }
        ACCESS_HANDLER = new RotatingFileHandler(LOG_DIR + File.separator + "access.log", maxSize, maxFiles, bufferSize);
        ERROR_HANDLER = new RotatingFileHandler(LOG_DIR + File.separator + "error.log", maxSize, maxFiles, bufferSize);
        // Note: the JVM shutdown hook is registered lazily by each RotatingFileHandler on its
        // first publish (see RotatingFileHandler#ensureShutdownHook). No hook is registered here,
        // so there is a single flush/close path and no concurrent double-flush on exit.
    }

    /**
     * Return a class-level logger instance.
     * <p>
     * Non-error output (debug/info/warn) is written to {@code access.log}, error output to {@code error.log}.
     * The logger name is the fully-qualified class name, so every line records its source.
     */
    public static Log getLog(Class<?> logCls) {
        Log log = LOGS.get(logCls);
        if (log == null) {
            synchronized (LOGS) {
                log = LOGS.get(logCls);
                if (log == null) {
                    LogImpl impl = new LogImpl(logCls.getName(), ACCESS_HANDLER, ERROR_HANDLER);
                    impl.setEnabled(enabled);
                    LOGS.put(logCls, impl);
                    log = impl;
                }
            }
        }
        return log;
    }

    /**
     * Globally enable or disable all loggers. When disabled, nothing is written.
     */
    public static void setEnabled(boolean enabled) {
        LogFactory.enabled = enabled;
        for (Log log : LOGS.values()) {
            log.setEnabled(enabled);
        }
    }

    /**
     * Set the global log level at runtime. Applies to all loggers immediately.
     */
    public static void setLevel(LogLevel level) {
        if (level != null) {
            globalLevel = level;
        }
    }

    /**
     * Return the current global log level.
     */
    public static LogLevel getLevel() {
        return globalLevel;
    }

    /**
     * Whether a record of the given severity should be emitted under the current level.
     */
    public static boolean isLoggable(LogLevel recordLevel) {
        return recordLevel.value >= globalLevel.value;
    }

    private LogFactory() {
    }
}

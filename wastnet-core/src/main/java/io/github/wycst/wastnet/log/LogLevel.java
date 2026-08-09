package io.github.wycst.wastnet.log;

/**
 * Logging levels for the internal framework logger.
 * <p>
 * Ordered by increasing severity; a configured level covers (allows) any record whose
 * severity is equal to or greater than it. Resolved from the system property
 * {@code wastnet.log.level} (default {@link #INFO}); runtime changes via
 * {@link LogFactory#setLevel(LogLevel)} apply globally to all loggers.
 *
 * @since 2026-7-25
 */
public enum LogLevel {

    DEBUG(0),
    INFO(1),
    WARN(2),
    ERROR(3),
    OFF(4);

    final int value;

    LogLevel(int value) {
        this.value = value;
    }

    /**
     * Parse a level name (case-insensitive); fall back to {@code dft} on null or unknown value.
     */
    public static LogLevel fromString(String s, LogLevel dft) {
        if (s == null || s.trim().isEmpty()) {
            return dft;
        }
        try {
            return LogLevel.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return dft;
        }
    }
}

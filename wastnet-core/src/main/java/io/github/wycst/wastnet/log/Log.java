package io.github.wycst.wastnet.log;

/**
 * Minimal log facade for internal framework use.
 * <p>
 * Level methods (debug/info/warn/error) are kept for call-site convenience. Each call is
 * filtered by the global log level configured via {@code wastnet.log.level}
 * (see {@link LogFactory}); a logger routes non-error output to {@code access.log} and
 * error output to {@code error.log}.
 *
 * @since 2025-6-15
 */
public interface Log {

    /**
     * Log at DEBUG level.
     *
     * @param msg  the message format, using {@code {}} placeholders
     * @param args the arguments referenced by the placeholders
     */
    void debug(String msg, Object... args);

    /**
     * Log at INFO level.
     *
     * @param msg  the message format, using {@code {}} placeholders
     * @param args the arguments referenced by the placeholders
     */
    void info(String msg, Object... args);

    /**
     * Echo the bare message to the console only (no file record).
     *
     * @param msg  the message format, using {@code {}} placeholders
     * @param args the arguments referenced by the placeholders
     */
    void console(String msg, Object... args);

    /**
     * Log at WARN level.
     *
     * @param msg  the message format, using {@code {}} placeholders
     * @param args the arguments referenced by the placeholders
     */
    void warn(String msg, Object... args);

    /**
     * Log at ERROR level.
     *
     * @param msg  the message format, using {@code {}} placeholders
     * @param args the arguments referenced by the placeholders
     */
    void error(String msg, Object... args);

    /**
     * Log at ERROR level with an associated throwable.
     *
     * @param msg        the message format, using {@code {}} placeholders
     * @param throwable  the exception to include in the error record
     * @param args       the arguments referenced by the placeholders
     */
    void error(String msg, Throwable throwable, Object... args);

    /**
     * Enable or disable this logger. When disabled, nothing is written.
     *
     * @param enabled true to enable logging, false to disable
     */
    void setEnabled(boolean enabled);

    /**
     * Whether this logger is currently enabled.
     *
     * @return true if logging is enabled
     */
    boolean isEnabled();

    /**
     * Whether DEBUG-level output is currently active. Combines this logger's
     * {@link #isEnabled()} switch with the global level resolved via
     * {@link LogFactory#isLoggable}. Use it to guard expensive debug arguments
     * so they are not evaluated when debug output is suppressed.
     */
    default boolean isDebugEnabled() {
        return isEnabled() && LogFactory.isLoggable(LogLevel.DEBUG);
    }
}

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

    void debug(String msg, Object... args);

    void info(String msg, Object... args);

    void warn(String msg, Object... args);

    void error(String msg, Object... args);

    void error(String msg, Throwable throwable, Object... args);

    /**
     * Enable or disable this logger. When disabled, nothing is written.
     */
    void setEnabled(boolean enabled);

    boolean isEnabled();
}

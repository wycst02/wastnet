package io.github.wycst.wastnet.log;

/**
 * Built-in {@link Log} implementation backed by two rotating file handlers:
 * non-error level output (debug/info/warn) is written to {@code access.log}, error output to
 * {@code error.log}. There is no level-based filtering; the only switch is
 * {@link #setEnabled(boolean)}.
 *
 * @since 2025-6-15
 */
class LogImpl implements Log {

    private static final LogFormatter FORMATTER = new LogFormatter();

    private final String loggerName;
    private final RotatingFileHandler accessHandler;
    private final RotatingFileHandler errorHandler;
    private volatile boolean enabled = true;

    public LogImpl(String loggerName, RotatingFileHandler accessHandler, RotatingFileHandler errorHandler) {
        this.loggerName = loggerName;
        this.accessHandler = accessHandler;
        this.errorHandler = errorHandler;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void debug(String msg, Object... args) {
        log(LogLevel.DEBUG, msg, args);
    }

    @Override
    public void info(String msg, Object... args) {
        log(LogLevel.INFO, msg, args);
    }

    @Override
    public void warn(String msg, Object... args) {
        log(LogLevel.WARN, msg, args);
    }

    @Override
    public void error(String msg, Object... args) {
        logError(msg, args, null);
    }

    @Override
    public void error(String msg, Throwable throwable, Object... args) {
        logError(msg, args, throwable);
    }

    @Override
    public void console(String msg, Object... args) {
        logConsole(msg, args);
    }

    private void log(LogLevel level, String msg, Object[] args) {
        if (!enabled || !LogFactory.isLoggable(level)) {
            return;
        }
        long millis = System.currentTimeMillis();
        accessHandler.publish(FORMATTER.format(level.name(), loggerName, millis, msg, args, null));
    }

    private void logError(String msg, Object[] args, Throwable thrown) {
        if (!enabled || !LogFactory.isLoggable(LogLevel.ERROR)) {
            return;
        }
        long millis = System.currentTimeMillis();
        errorHandler.publish(FORMATTER.format(LogLevel.ERROR.name(), loggerName, millis, msg, args, thrown), true);
    }

    private void logConsole(String msg, Object[] args) {
        if (!enabled || !LogFactory.isLoggable(LogLevel.INFO)) {
            return;
        }
        // Console feedback is dev-facing: skip the file log's header (timestamp, thread, level,
        // fully-qualified logger name) and print just the rendered message. The {} placeholder
        // semantics stay identical to the file output via LogFormatter.replacePlaceholder.
        System.out.println(LogFormatter.replacePlaceholder(msg, "{}", args));
    }
}

package io.github.wycst.wastnet.log;

import io.github.wycst.wastnet.util.GeneralDate;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.TimeZone;

/**
 * Default log formatter. Output format (shared by access.log and error.log):
 * <pre>
 *     2025-6-15 12:00:00.123 [threadName] LEVEL loggerName - message
 * </pre>
 */
public class LogFormatter {

    /**
     * Cache the default timezone once. {@link TimeZone#getDefault()} clones the default zone on
     * every call, so resolving it per log line would allocate a {@code TimeZone} object for each
     * emitted record. A static constant trades runtime timezone changes (acceptable for a log
     * formatter) for zero per-line allocation.
     */
    private static final TimeZone DEFAULT_TZ = TimeZone.getDefault();

    public String format(String level, String loggerName, long millis, String message, Object[] parameters, Throwable thrown) {

        StringBuilder sb = new StringBuilder();
        appendMillis(sb, millis);
        sb.append(" [");
        sb.append(Thread.currentThread().getName());
        sb.append("] ");
        sb.append(level);
        sb.append(" ");
        sb.append(loggerName);
        sb.append(" - ");

        // message
        String rendered = message;
        if (rendered != null && parameters != null && parameters.length > 0) {
            // replace placeholders
            rendered = replacePlaceholder(rendered, "{}", parameters);
        }
        sb.append(rendered);
        sb.append("\n");

        if (thrown != null) {
            sb.append(getThrowableContent(thrown));
        }

        return sb.toString();
    }

    private void appendMillis(StringBuilder sb, long millis) {
        // Decompose the timestamp into "yyyy-MM-dd HH:mm:ss.SSS" without allocating a
        // per-thread Calendar; lock-free and safe for both platform and virtual threads.
        GeneralDate.writeYmdHms(sb, millis, DEFAULT_TZ);
    }

    /***
     * <p> eg: message: "{}, hello", placeholder: "{}", parameters: ["xx"] -> xx, hello</p>
     *
     * @param message     the message template
     * @param placeholder the placeholder to replace (usually "{}")
     * @param parameters  the values to substitute
     * @return the message with placeholders replaced
     */
    public static String replacePlaceholder(String message, String placeholder, Object... parameters) {
        if(message == null) {
            return "null";
        }
        int parameterCount;
        if (placeholder == null || placeholder.isEmpty() || parameters == null || (parameterCount = parameters.length) == 0) {
            return message;
        }
        int placeholderIndex = message.indexOf(placeholder);
        if (placeholderIndex < 0) {
            return message;
        }
        StringBuilder buffer = new StringBuilder();
        int fromIndex = 0;
        int placeholderLen = placeholder.length();
        int i = 0;
        while (placeholderIndex > -1) {
            buffer.append(message, fromIndex, placeholderIndex);
            if (i < parameterCount) {
                buffer.append(parameters[i++]);
            } else {
                buffer.append(placeholder);
            }
            fromIndex = placeholderIndex + placeholderLen;
            placeholderIndex = message.indexOf(placeholder, fromIndex);
        }
        if (fromIndex < message.length()) {
            buffer.append(message, fromIndex, message.length());
        }

        return buffer.toString();
    }

    public static String getThrowableContent(Throwable t) {
        if (t == null)
            return null;
        StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) {
            t.printStackTrace(pw);
            return sw.toString();
        }
    }
}

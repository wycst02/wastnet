package io.github.wycst.wastnet.log;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LogFormatter}.
 */
public class LogFormatterTest {

    private final LogFormatter formatter = new LogFormatter();

    // ==================== replacePlaceholder ====================

    @Test
    public void testReplacePlaceholderNoPlaceholder() {
        String result = LogFormatter.replacePlaceholder("hello world", "{}");
        Assertions.assertEquals("hello world", result);
    }

    @Test
    public void testReplacePlaceholderNoMatchWithParams() {
        // placeholder absent but parameters non-empty: skips the early return and hits "placeholderIndex < 0"
        String result = LogFormatter.replacePlaceholder("no placeholder here", "{}", "x");
        Assertions.assertEquals("no placeholder here", result);
    }

    @Test
    public void testReplacePlaceholderOneParam() {
        String result = LogFormatter.replacePlaceholder("hello {}", "{}", "world");
        Assertions.assertEquals("hello world", result);
    }

    @Test
    public void testReplacePlaceholderMultipleParams() {
        String result = LogFormatter.replacePlaceholder("{} + {} = {}", "{}", 1, 2, 3);
        Assertions.assertEquals("1 + 2 = 3", result);
    }

    @Test
    public void testReplacePlaceholderMorePlaceholdersThanParams() {
        String result = LogFormatter.replacePlaceholder("a={} b={} c={}", "{}", "x", "y");
        Assertions.assertEquals("a=x b=y c={}", result);
    }

    @Test
    public void testReplacePlaceholderNullPlaceholder() {
        String result = LogFormatter.replacePlaceholder("hello", null, "world");
        Assertions.assertEquals("hello", result);
    }

    @Test
    public void testReplacePlaceholderEmptyPlaceholder() {
        String result = LogFormatter.replacePlaceholder("hello", "", "world");
        Assertions.assertEquals("hello", result);
    }

    @Test
    public void testReplacePlaceholderNullParameters() {
        String result = LogFormatter.replacePlaceholder("hello {}", "{}", (Object[]) null);
        Assertions.assertEquals("hello {}", result);
    }

    @Test
    public void testReplacePlaceholderEmptyParameters() {
        String result = LogFormatter.replacePlaceholder("hello {}", "{}");
        Assertions.assertEquals("hello {}", result);
    }

    @Test
    public void testReplacePlaceholderCustomPlaceholder() {
        String result = LogFormatter.replacePlaceholder("say %s to %s", "%s", "hello", "world");
        Assertions.assertEquals("say hello to world", result);
    }

    @Test
    public void testReplacePlaceholderConsecutive() {
        String result = LogFormatter.replacePlaceholder("{}{}", "{}", "a", "b");
        Assertions.assertEquals("ab", result);
    }

    @Test
    public void testReplacePlaceholderNullMessage() {
        String result = LogFormatter.replacePlaceholder(null, "{}", "world");
        Assertions.assertEquals("null", result);
    }

    // ==================== format ====================

    @Test
    public void testFormatSimpleMessage() {
        String result = formatter.format("INFO", "TestLogger", System.currentTimeMillis(), "test message", null, null);
        Assertions.assertTrue(result.contains("test message"));
        Assertions.assertTrue(result.contains("INFO"));
        Assertions.assertTrue(result.contains("TestLogger"));
    }

    @Test
    public void testFormatWithParameters() {
        String result = formatter.format("INFO", "Test", System.currentTimeMillis(), "hello {}!", new Object[]{"world"}, null);
        Assertions.assertTrue(result.contains("hello world!"));
    }

    @Test
    public void testFormatWithException() {
        String result = formatter.format("WARN", "Test", System.currentTimeMillis(), "error occurred", null, new RuntimeException("test exception"));
        Assertions.assertTrue(result.contains("test exception"));
        Assertions.assertTrue(result.contains("WARN"));
    }

    @Test
    public void testFormatWithMessageOnly() {
        String result = formatter.format("ERROR", "Test", System.currentTimeMillis(), "formatted error", null, null);
        Assertions.assertTrue(result.contains("formatted error"));
        Assertions.assertTrue(result.contains("ERROR"));
    }

    @Test
    public void testFormatDebugLevel() {
        String result = formatter.format("DEBUG", "Test", System.currentTimeMillis(), "debug message", null, null);
        Assertions.assertTrue(result.contains("debug message"));
        Assertions.assertTrue(result.contains("DEBUG"));
    }

    @Test
    public void testFormatHasNewline() {
        String result = formatter.format("INFO", "T", System.currentTimeMillis(), "msg", null, null);
        Assertions.assertTrue(result.endsWith("\n"));
    }

    @Test
    public void testFormatTimestampAppears() {
        String result = formatter.format("INFO", "Logger", System.currentTimeMillis(), "test", null, null);
        // Should contain date pattern like "2026-06-29"
        Assertions.assertTrue(result.matches("(?s).*\\d{4}-\\d{2}-\\d{2}.*"));
    }

    @Test
    public void testFormatNullMessage() {
        // null message makes rendered == null, so "null" is appended literally
        String result = formatter.format("INFO", "Test", System.currentTimeMillis(), null, null, null);
        Assertions.assertTrue(result.contains("null"));
        Assertions.assertTrue(result.contains("INFO"));
    }

    // ==================== getThrowableContent ====================

    @Test
    public void testGetThrowableContentNullReturnsNull() {
        Assertions.assertNull(LogFormatter.getThrowableContent(null));
    }

    @Test
    public void testGetThrowableContentContainsMessage() {
        RuntimeException ex = new RuntimeException("something went wrong");
        String content = LogFormatter.getThrowableContent(ex);
        Assertions.assertNotNull(content);
        Assertions.assertTrue(content.contains("something went wrong"));
    }

    @Test
    public void testGetThrowableContentContainsStackTrace() {
        RuntimeException ex = new RuntimeException("stack test");
        String content = LogFormatter.getThrowableContent(ex);
        Assertions.assertTrue(content.contains("LogFormatterTest"));
    }
}

package io.github.wycst.wastnet.http;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.TimeZone;

/**
 * Targeted tests for {@link HttpDate}.
 * Fills remaining branch gaps not covered by HttpCoreUtilTest.
 *
 * @author wangyc
 */
public class HttpDateTest {

    private static final TimeZone GMT = TimeZone.getTimeZone("GMT");

    // ==================== isLeapYear C=false branch ====================

    @Test
    public void testLeapYearCenturialNonLeap() {
        // Year 2100: divisible by 100 but not 400 → C=false
        long millis2100 = 4107542400000L; // approximately 2100-03-01 GMT
        HttpDate date = new HttpDate(millis2100, GMT);
        Assertions.assertEquals(2100, date.getYear());
        Assertions.assertEquals(3, date.getMonth());
    }

    // ==================== setTime while loop branches ====================

    @Test
    public void testDecemberMonth() {
        // December (month=12) triggers m >= 11 in while loop
        long decMillis = 1733011200000L; // 2024-12-01 GMT
        HttpDate date = new HttpDate(decMillis, GMT);
        Assertions.assertEquals(12, date.getMonth());
    }

    // ==================== isSameDay false branch ====================

    @Test
    public void testIsSameDayFalse() {
        long now = System.currentTimeMillis();
        long nextDay = now + 48 * 60 * 60 * 1000L;
        Assertions.assertFalse(HttpDate.isSameDay(now, nextDay));
    }

    // ==================== updateCacheIfNeeded cross-day boundary ====================

    @Test
    public void testUpdateCacheIfNeededCrossDay() throws Exception {
        HttpDate.getCurrentDateHeaderLineBytes();
        java.lang.reflect.Field lastUpdateField = HttpDate.class.getDeclaredField("lastUpdateMillis");
        lastUpdateField.setAccessible(true);
        long yesterday = System.currentTimeMillis() - 48 * 60 * 60 * 1000L;
        lastUpdateField.setLong(null, yesterday);
        byte[] line = HttpDate.getCurrentDateHeaderLineBytes();
        Assertions.assertEquals(37, line.length);
        Assertions.assertEquals('d', line[0]);
    }

    // ==================== getDateHeaderBytes with various dates ====================

    @Test
    public void testGetDateHeaderBytesDifferentDates() {
        long now = System.currentTimeMillis();
        byte[] bytes = HttpDate.getDateHeaderBytes(now);
        Assertions.assertEquals(29, bytes.length);
        long past = -12623256000000L; // year 1570
        byte[] pastBytes = HttpDate.getDateHeaderBytes(past);
        Assertions.assertEquals(29, pastBytes.length);
    }
}

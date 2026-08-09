package io.github.wycst.wastnet.util;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.TimeZone;

/**
 * Unit tests for {@link GeneralDate}. Uses {@code java.time} as an independent
 * oracle so the decomposed fields are cross-checked against the JDK.
 */
public class GeneralDateTest {

    private static final TimeZone GMT = TimeZone.getTimeZone("GMT");

    private static long millis(int y, int mo, int d, int h, int mi, int s) {
        return ZonedDateTime.of(y, mo, d, h, mi, s, 0, ZoneId.of("GMT")).toInstant().toEpochMilli();
    }

    @Test
    public void testConstructorDecomposesFields() {
        long t = millis(2020, 2, 29, 12, 34, 56);
        GeneralDate gd = new GeneralDate(t, GMT);
        Assertions.assertEquals(2020, gd.getYear());
        Assertions.assertEquals(2, gd.getMonth());
        Assertions.assertEquals(29, gd.getDay());
        Assertions.assertEquals(12, gd.getHourOfDay());
        Assertions.assertEquals(34, gd.getMinute());
        Assertions.assertEquals(56, gd.getSecond());
    }

    @Test
    public void testLeapYearFeb29() {
        GeneralDate gd = new GeneralDate(millis(2020, 2, 29, 0, 0, 0), GMT);
        Assertions.assertEquals(2, gd.getMonth());
        Assertions.assertEquals(29, gd.getDay());
    }

    @Test
    public void testNonLeapYearFeb28() {
        GeneralDate gd = new GeneralDate(millis(2021, 2, 28, 0, 0, 0), GMT);
        Assertions.assertEquals(2, gd.getMonth());
        Assertions.assertEquals(28, gd.getDay());
    }

    @Test
    public void testGetDayOfWeek() {
        // 2024-01-01 is Monday (GeneralDate encoding: Monday = 2)
        GeneralDate gd = new GeneralDate(millis(2024, 1, 1, 0, 0, 0), GMT);
        Assertions.assertEquals(2, gd.getDayOfWeek());
        assertDowMatches(2023, 12, 25);
        assertDowMatches(2024, 7, 7);
        assertDowMatches(2000, 2, 29);
    }

    private void assertDowMatches(int y, int mo, int d) {
        long t = millis(y, mo, d, 10, 0, 0);
        GeneralDate gd = new GeneralDate(t, GMT);
        int javaDow = ZonedDateTime.of(y, mo, d, 10, 0, 0, 0, ZoneId.of("GMT")).getDayOfWeek().getValue();
        // GeneralDate: 1=Sunday..7=Saturday ; java.time: 1=Monday..7=Sunday
        int expectedGd = (javaDow % 7) + 1;
        Assertions.assertEquals(expectedGd, gd.getDayOfWeek(), "dow mismatch for " + y + "-" + mo + "-" + d);
    }

    @Test
    public void testWriteYmdHmsFormat() {
        StringBuilder sb = new StringBuilder();
        GeneralDate.writeYmdHms(sb, millis(2020, 2, 29, 12, 34, 56), GMT);
        Assertions.assertEquals("2020-02-29 12:34:56.000", sb.toString());

        StringBuilder sb2 = new StringBuilder();
        long t = millis(2021, 3, 5, 9, 8, 7) + 123;
        GeneralDate.writeYmdHms(sb2, t, GMT);
        Assertions.assertEquals("2021-03-05 09:08:07.123", sb2.toString());
    }

    @Test
    public void testWriteYmdHmsWithTimeZoneOffset() {
        TimeZone plus8 = TimeZone.getTimeZone("GMT+08:00");
        // 2020-02-29 12:34:56 GMT == 2020-02-29 20:34:56 +08:00
        long t = millis(2020, 2, 29, 12, 34, 56);
        StringBuilder sb = new StringBuilder();
        GeneralDate.writeYmdHms(sb, t, plus8);
        Assertions.assertEquals("2020-02-29 20:34:56.000", sb.toString());
    }

    @Test
    public void testWriteYmdHmsTwoDigitMonth() {
        // month >= 10 must be appended without a leading zero (covers the "month > 9" branch)
        StringBuilder sb = new StringBuilder();
        GeneralDate.writeYmdHms(sb, millis(2020, 12, 29, 12, 34, 56), GMT);
        Assertions.assertEquals("2020-12-29 12:34:56.000", sb.toString());

        StringBuilder sb2 = new StringBuilder();
        GeneralDate.writeYmdHms(sb2, millis(2021, 10, 5, 9, 8, 7), GMT);
        Assertions.assertEquals("2021-10-05 09:08:07.000", sb2.toString());
    }

    @Test
    public void testConstructorWithTimeZoneOffset() {
        TimeZone plus8 = TimeZone.getTimeZone("GMT+08:00");
        long t = millis(2020, 2, 29, 12, 34, 56); // GMT instant
        GeneralDate gd = new GeneralDate(t, plus8);
        // +8h -> 20:34:56, same day
        Assertions.assertEquals(20, gd.getHourOfDay());
        Assertions.assertEquals(34, gd.getMinute());
        Assertions.assertEquals(2020, gd.getYear());
        Assertions.assertEquals(29, gd.getDay());
    }
}

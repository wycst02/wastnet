package io.github.wycst.wastnet.examples.http;

import io.github.wycst.wastnet.http.HttpDate;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.TimeZone;

/**
 * Validate HttpDate correctness against java.time over an 8-year range at 1-second intervals.
 * <p>
 * Covers 4 years before and 4 years after the current time, testing all leap-year
 * transitions, DST boundaries (using GMT/UTC), and day-of-week alignment.
 * <p>
 * Run via {@code main()} — not a JUnit test.
 */
public class HttpDateValidationTest {

    private static final TimeZone GMT = TimeZone.getTimeZone("GMT");
    private static final ZoneOffset UTC = ZoneOffset.UTC;
    private static final long MS_PER_SECOND = 1000L;
    private static final long MS_PER_YEAR  = 365L * 86400 * 1000;
    private static final long MS_PER_LEAP  = 366L * 86400 * 1000;

    public static void main(String[] args) {
        long now = System.currentTimeMillis();

        // 10 years backward, 10 years forward ≈ 20 years total
        long start = now - 10L * MS_PER_YEAR - 3 * MS_PER_LEAP; // safe margin for leap years
        long end   = now + 10L * MS_PER_YEAR + 3 * MS_PER_LEAP;
        long total = (end - start) / MS_PER_SECOND + 1;

        System.out.printf("Validating HttpDate from %s to %s (%s ticks)%n",
                Instant.ofEpochMilli(start).atZone(UTC).toLocalDate(),
                Instant.ofEpochMilli(end).atZone(UTC).toLocalDate(),
                formatCount(total));

        long reportInterval = Math.max(total / 20, 1); // ~5% steps
        long nextReport = reportInterval;

        long mark = System.nanoTime();
        for (long i = 0, ts = start; ts <= end; ts += MS_PER_SECOND, ++i) {
            HttpDate hd = new HttpDate(ts, GMT);
            ZonedDateTime ref = Instant.ofEpochMilli(ts).atZone(UTC);

            int actualYear   = hd.getYear();
            int actualMonth  = hd.getMonth();
            int actualDay    = hd.getDay();
            int actualHour   = hd.getHourOfDay();
            int actualMinute = hd.getMinute();
            int actualSecond = hd.getSecond();
            int actualDow    = hd.getDayOfWeek();

            int expectYear   = ref.getYear();
            int expectMonth  = ref.getMonthValue();
            int expectDay    = ref.getDayOfMonth();
            int expectHour   = ref.getHour();
            int expectMinute = ref.getMinute();
            int expectSecond = ref.getSecond();
            int expectDow    = ref.getDayOfWeek().getValue() % 7 + 1; // Mon=1 → Sun=1 mapping

            if (actualYear != expectYear || actualMonth != expectMonth || actualDay != expectDay ||
                actualHour != expectHour || actualMinute != expectMinute || actualSecond != expectSecond ||
                actualDow != expectDow) {
                System.err.printf("MISMATCH at ts=%d (%s)%n", ts, ref.toLocalDateTime());
                System.err.printf("  HttpDate: %04d-%02d-%02dT%02d:%02d:%02d dow=%d%n",
                        actualYear, actualMonth, actualDay, actualHour, actualMinute, actualSecond, actualDow);
                System.err.printf("  Expect:   %04d-%02d-%02dT%02d:%02d:%02d dow=%d%n",
                        expectYear, expectMonth, expectDay, expectHour, expectMinute, expectSecond, expectDow);
                System.err.println("FAIL — HttpDate produced incorrect result.");
                System.exit(1);
            }

            if (i >= nextReport) {
                long elapsed = System.nanoTime() - mark;
                double pct = (double) i / total * 100;
                System.out.printf("  [%5.1f%%] %s / %s — %,d ms%n",
                        pct, formatCount(i), formatCount(total), elapsed / 1_000_000);
                nextReport += reportInterval;
            }
        }

        long elapsed = System.nanoTime() - mark;
        System.out.printf("%n=== Done in %,d ms ===%n", elapsed / 1_000_000);
        System.out.println("ALL PASS — HttpDate is correct over the full range.");
    }

    private static String formatCount(long n) {
        if (n < 1000) return String.valueOf(n);
        if (n < 1_000_000) return String.format("%.1fK", n / 1000.0);
        if (n < 1_000_000_000) return String.format("%.1fM", n / 1_000_000.0);
        return String.format("%.1fG", n / 1_000_000_000.0);
    }
}

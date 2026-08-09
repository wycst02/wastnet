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
package io.github.wycst.wastnet.util;

import java.util.TimeZone;

/**
 * Generic broken-down date representation: decomposes an epoch millisecond timestamp (plus a
 * timezone) into year/month/day/hour/minute/second fields. Used as the base for HTTP date
 * formatting ({@code HttpDate}) and for fast, lock-free log timestamp writing.
 *
 * @author wangy
 */
public class GeneralDate {

    // Milliseconds from year 1 (0001).1.1 to 1970.1.1
    public static final long RELATIVE_MILLS = 62135769600000L;
    // 1970-01-01 was Thursday (5 in 1=Sunday format)
    private static final int RELATIVE_DAY_OF_WEEK = 5;

    private static final int[] DAYS_ACCUM = {0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334};
    private static final int[] DAYS_ACCUM_LEAP = {0, 31, 60, 91, 121, 152, 182, 213, 244, 274, 305, 335};

    private final long epochDays;
    private final int year, month, day;
    private final int hourOfDay, minute, second;

    /**
     * Decompose the given timestamp into year/month/day/hour/minute/second fields. Uses the
     * timezone's raw offset (no DST), which is correct for fixed-offset zones such as GMT.
     */
    public GeneralDate(long timeMillis, TimeZone timeZone) {
        long ms = timeMillis + RELATIVE_MILLS + timeZone.getRawOffset();

        long seconds = ms / 1000;
        long days = seconds / 86400;
        int daySeconds = (int) (seconds - days * 86400);

        this.epochDays = timeMillis / 86400000;

        this.hourOfDay = daySeconds / 3600;
        this.minute = (daySeconds - this.hourOfDay * 3600) / 60;
        this.second = daySeconds % 60;

        // year/month/day from days since epoch
        long y = (days * 400) / 146097 + 1;
        long offset = (y - 1) * 365 + (y - 1) / 4 - (y - 1) / 100 + (y - 1) / 400 + 2;
        while (offset > days) {
            offset -= isLeapYear((int) --y) ? 366 : 365;
        }
        while (offset + (isLeapYear((int) y) ? 366 : 365) <= days) {
            offset += isLeapYear((int) y) ? 366 : 365;
            y++;
        }
        this.year = (int) y;

        int doy = (int) (days - offset);
        int[] accum = isLeapYear(this.year) ? DAYS_ACCUM_LEAP : DAYS_ACCUM;
        int m = 0;
        while (m < 11 && doy >= accum[m + 1]) {
            m++;
        }
        this.month = m + 1;
        this.day = doy - accum[m] + 1;
    }

    public int getYear() {
        return year;
    }

    public int getMonth() {
        return month;
    }

    public int getDay() {
        return day;
    }

    public int getHourOfDay() {
        return hourOfDay;
    }

    public int getMinute() {
        return minute;
    }

    public int getSecond() {
        return second;
    }

    /**
     * Day of week (1=Sunday, 2=Monday ... 7=Saturday).
     */
    public int getDayOfWeek() {
        int dow = (int) ((epochDays + RELATIVE_DAY_OF_WEEK - 1) % 7 + 1);
        return dow <= 0 ? dow + 7 : dow;
    }

    private static boolean isLeapYear(int y) {
        return (y & 3) == 0 && (y % 100 != 0 || y % 400 == 0);
    }

    /**
     * Write a timestamp as "yyyy-MM-dd HH:mm:ss.SSS" into the given builder, using the supplied
     * timezone (DST-aware via {@link TimeZone#getOffset(long)}). Lock-free and allocation-free,
     * so it is safe for both platform and virtual threads — replacing the old
     * ThreadLocal&lt;Calendar&gt; approach used by the log formatter.
     */
    public static void writeYmdHms(StringBuilder sb, long timeMillis, TimeZone timeZone) {
        long ms = timeMillis + RELATIVE_MILLS + timeZone.getOffset(timeMillis);
        long seconds = ms / 1000;
        long days = seconds / 86400;
        int daySeconds = (int) (seconds - days * 86400);
        int hour = daySeconds / 3600;
        int minute = (daySeconds - hour * 3600) / 60;
        int second = daySeconds % 60;
        int millis = (int) (ms % 1000); // ms = timeMillis + RELATIVE_MILLS + offset stays positive for any valid timestamp, so millis is already in [0,1000)

        long y = (days * 400) / 146097 + 1;
        long offset = (y - 1) * 365 + (y - 1) / 4 - (y - 1) / 100 + (y - 1) / 400 + 2;
        while (offset > days) {
            offset -= isLeapYear((int) --y) ? 366 : 365;
        }
        while (offset + (isLeapYear((int) y) ? 366 : 365) <= days) {
            offset += isLeapYear((int) y) ? 366 : 365;
            y++;
        }
        int year = (int) y;

        int doy = (int) (days - offset);
        int[] accum = isLeapYear(year) ? DAYS_ACCUM_LEAP : DAYS_ACCUM;
        int m = 0;
        while (m < 11 && doy >= accum[m + 1]) {
            m++;
        }
        int month = m + 1;
        int day = doy - accum[m] + 1;

        sb.append(year).append('-');
        if (month > 9) {
            sb.append(month);
        } else {
            sb.append('0').append(month);
        }
        sb.append('-');
        if (day > 9) {
            sb.append(day);
        } else {
            sb.append('0').append(day);
        }
        sb.append(' ');
        if (hour > 9) {
            sb.append(hour);
        } else {
            sb.append('0').append(hour);
        }
        sb.append(':');
        if (minute > 9) {
            sb.append(minute);
        } else {
            sb.append('0').append(minute);
        }
        sb.append(':');
        if (second > 9) {
            sb.append(second);
        } else {
            sb.append('0').append(second);
        }
        int dotIndex = sb.length();
        sb.append(millis + 1000);
        sb.setCharAt(dotIndex, '.');
    }
}

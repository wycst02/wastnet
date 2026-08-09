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
package io.github.wycst.wastnet.http;

import io.github.wycst.wastnet.util.GeneralDate;

import java.util.TimeZone;

/**
 * Lightweight date formatting for HTTP Date header.
 * <p>
 * The date decomposition (timestamp to fields) is inherited from {@link GeneralDate}; this
 * class adds ultra-fast byte-array formatting for HTTP Date headers.
 *
 * @author wangy
 * @since 2022/8/11
 */
public class HttpDate extends GeneralDate {

    // ==================== Date formatting constants ====================

    // Pre-defined byte arrays for fast date formatting
    static final byte[][] WEEK_DAYS = {
            {'S', 'u', 'n'}, {'M', 'o', 'n'}, {'T', 'u', 'e'},
            {'W', 'e', 'd'}, {'T', 'h', 'u'}, {'F', 'r', 'i'}, {'S', 'a', 't'}
    };
    static final byte[][] MONTHS = {
            {'J', 'a', 'n'}, {'F', 'e', 'b'}, {'M', 'a', 'r'}, {'A', 'p', 'r'},
            {'M', 'a', 'y'}, {'J', 'u', 'n'}, {'J', 'u', 'l'}, {'A', 'u', 'g'},
            {'S', 'e', 'p'}, {'O', 'c', 't'}, {'N', 'o', 'v'}, {'D', 'e', 'c'}
    };
    // Pre-cached GMT timezone
    static final TimeZone GMT_TIMEZONE = TimeZone.getTimeZone("GMT");

    // ==================== Date header cache ====================

    // Date header line cached byte array: "Date: EEE, dd MMM yyyy HH:mm:ss GMT\r\n" (37 bytes)
    private static final byte[] DATE_HEADER_LINE_BYTES = new byte[37];
    private static long lastUpdateMillis;
    private static final long DAY_MILLIS = 24 * 60 * 60 * 1000L;
    private static final long SECOND_MILLIS = 1000L;

    static {
        // Initialize truly static characters that never change
        // Date header prefix: "Date: " (6 bytes)
        DATE_HEADER_LINE_BYTES[1] = 'a';
        DATE_HEADER_LINE_BYTES[2] = 't';
        DATE_HEADER_LINE_BYTES[3] = 'e';
        DATE_HEADER_LINE_BYTES[4] = ':';
        DATE_HEADER_LINE_BYTES[5] = ' ';
        // Date format: "EEE, dd MMM yyyy HH:mm:ss GMT" (29 bytes, starting from index 6)
        DATE_HEADER_LINE_BYTES[9] = ',';   // Comma after weekday
        DATE_HEADER_LINE_BYTES[10] = ' ';   // Space after comma
        DATE_HEADER_LINE_BYTES[13] = ' ';   // Space after day
        DATE_HEADER_LINE_BYTES[17] = ' ';  // Space after month
        DATE_HEADER_LINE_BYTES[22] = ' ';  // Space after year
        DATE_HEADER_LINE_BYTES[25] = ':';  // First colon
        DATE_HEADER_LINE_BYTES[28] = ':';  // Second colon
        DATE_HEADER_LINE_BYTES[31] = ' ';  // Space before GMT
        DATE_HEADER_LINE_BYTES[32] = 'G';  // G
        DATE_HEADER_LINE_BYTES[33] = 'M';  // M
        DATE_HEADER_LINE_BYTES[34] = 'T';  // T
        // CRLF suffix (2 bytes)
        DATE_HEADER_LINE_BYTES[35] = '\r';
        DATE_HEADER_LINE_BYTES[36] = '\n';

        // Initialize date cache
        updateDateCache(System.currentTimeMillis());

        // Update Date header case based on initial configuration
        updateDateHeaderCase();
    }

    public HttpDate(long timeMillis, TimeZone timeZone) {
        super(timeMillis, timeZone);
    }

    // ==================== Static formatting helpers ====================

    /**
     * Update date portion in target byte array at specified offset
     * Format: "EEE, dd MMM yyyy HH:mm:ss GMT" (29 bytes)
     *
     * @param currentTimeMillis timestamp in milliseconds
     * @param targetBytes       target byte array to write date format
     * @param offset            offset in targetBytes where date portion starts
     */
    static void updateDatePart(long currentTimeMillis, byte[] targetBytes, int offset) {
        HttpDate gd = new HttpDate(currentTimeMillis, GMT_TIMEZONE);
        int dayOfWeek = gd.getDayOfWeek();
        int year = gd.getYear();

        // Update only the dynamic parts (weekday, day, month, year, time)
        System.arraycopy(WEEK_DAYS[dayOfWeek - 1], 0, targetBytes, offset, 3);
        HttpUnsafe.writeTwoDigitChar(targetBytes, offset + 5, gd.getDay());
        System.arraycopy(MONTHS[gd.getMonth() - 1], 0, targetBytes, offset + 8, 3);
        // Full year update using arithmetic optimization
        int yearDiv100 = (int) (year * 1374389535L >> 37);
        HttpUnsafe.writeTwoDigitChar(targetBytes, offset + 12, yearDiv100);
        HttpUnsafe.writeTwoDigitChar(targetBytes, offset + 14, year - yearDiv100 * 100);

        // Update time portion (hour, minute, second)
        HttpUnsafe.writeTwoDigitChar(targetBytes, offset + 17, gd.getHourOfDay());
        HttpUnsafe.writeTwoDigitChar(targetBytes, offset + 20, gd.getMinute());
        HttpUnsafe.writeTwoDigitChar(targetBytes, offset + 23, gd.getSecond());
    }

    private static void updateDateCache(long currentTimeMillis) {
        updateDatePart(lastUpdateMillis = currentTimeMillis, DATE_HEADER_LINE_BYTES, 6);
    }

    private static void updateTimeInCache(long currentTimeMillis) {
        HttpDate gd = new HttpDate(currentTimeMillis, GMT_TIMEZONE);

        // Only update time portion - all static characters are already set
        // Offset by 6 to skip "Date: " prefix
        HttpUnsafe.writeTwoDigitChar(DATE_HEADER_LINE_BYTES, 23, gd.getHourOfDay());
        HttpUnsafe.writeTwoDigitChar(DATE_HEADER_LINE_BYTES, 26, gd.getMinute());
        HttpUnsafe.writeTwoDigitChar(DATE_HEADER_LINE_BYTES, 29, gd.getSecond());
        lastUpdateMillis = currentTimeMillis;
    }

    /**
     * Get current date header line as byte array, update cache if necessary
     * Returns complete header line: "Date: EEE, dd MMM yyyy HH:mm:ss GMT\r\n" (37 bytes)
     *
     * @return byte array representation of current date header line
     */
    static byte[] getCurrentDateHeaderLineBytes() {
        // For HTTP Date header, slight inconsistency is acceptable
        // The worst case is returning a slightly outdated date (within 1 second)
        // This is much better than the synchronization overhead
        updateCacheIfNeeded(System.currentTimeMillis());

        return DATE_HEADER_LINE_BYTES;
    }

    /**
     * Update cache based on time difference
     * Combines check and update logic for better performance
     * Note: Both updateDateCache and updateTimeInCache will update lastUpdateMillis
     */
    private static void updateCacheIfNeeded(long currentTimeMillis) {
        long currentSecond = currentTimeMillis / SECOND_MILLIS;
        long lastUpdateSecond = lastUpdateMillis / SECOND_MILLIS;

        // Seconds changed, need to check what kind of update
        if (currentSecond != lastUpdateSecond) {
            if (!isSameDay(currentTimeMillis, lastUpdateMillis)) {
                updateDateCache(currentTimeMillis); // Cross-day boundary
            } else {
                updateTimeInCache(currentTimeMillis); // Same day, just time changed
            }
        }
        // Same second, no update needed
    }

    /**
     * Update Date header first byte based on configuration
     * Only modifies the first character ('d' to 'D' for titlecase, or 'D' to 'd' for lowercase)
     */
    static void updateDateHeaderCase() {
        if (HttpHeaderUtils.isTitleCase()) {
            // Convert first letter to uppercase: 'd' -> 'D'
            DATE_HEADER_LINE_BYTES[0] = (byte) ('D');
        } else {
            // Convert first letter to lowercase: 'D' -> 'd'
            DATE_HEADER_LINE_BYTES[0] = (byte) ('d');
        }
    }

    /**
     * Get date as byte array for specified timestamp (date only, without header prefix)
     * Creates a new byte array with RFC 7231 date format
     * Format: "EEE, dd MMM yyyy HH:mm:ss GMT" (29 bytes)
     *
     * @param timestampMillis timestamp in milliseconds
     * @return byte array representation of GMT date for the given timestamp
     */
    static byte[] getDateHeaderBytes(long timestampMillis) {
        byte[] dateBytes = new byte[29];
        // Copy static characters from DATE_HEADER_LINE_BYTES (skip "Date: " prefix)
        System.arraycopy(DATE_HEADER_LINE_BYTES, 6, dateBytes, 0, 29);
        updateDatePart(timestampMillis, dateBytes, 0);
        return dateBytes;
    }

    /**
     * Check if two timestamps fall on the same GMT day
     *
     * @param sourceTimemills first timestamp in milliseconds
     * @param targetTimeMills second timestamp in milliseconds
     * @return true if both timestamps are in the same GMT day, false otherwise
     */
    static boolean isSameDay(long sourceTimemills, long targetTimeMills) {
        return (sourceTimemills + GMT_TIMEZONE.getRawOffset() + GeneralDate.RELATIVE_MILLS) / DAY_MILLIS == (targetTimeMills + GMT_TIMEZONE.getRawOffset() + GeneralDate.RELATIVE_MILLS) / DAY_MILLIS;
    }
}

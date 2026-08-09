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
package io.github.wycst.wastnet.http.h2;

import java.util.Arrays;

/**
 * HTTP/2 HPACK header table containing static and dynamic tables.
 * <p>
 * Implements RFC 7541 (HPACK: Header Compression for HTTP/2):
 * <ul>
 *   <li>Static table: 61 predefined headers (RFC 7541 Appendix A)</li>
 *   <li>Dynamic table: Grows/shrinks during session, max size controlled by SETTINGS_HEADER_TABLE_SIZE</li>
 *   <li>Index address space: 1-61 (static), 62+ (dynamic, 62=newest)</li>
 * </ul>
 * <p>
 * Thread-safety: NOT thread-safe. One instance per HTTP/2 connection.
 *
 * @author wangyc
 */
final class Http2HpackTable {

    private static final Http2Header[] STATIC_HEADER_TABLE = new Http2Header[62];

    static {
        STATIC_HEADER_TABLE[1] = new Http2Header(":authority");
        STATIC_HEADER_TABLE[2] = new Http2Header(":method", "GET");
        STATIC_HEADER_TABLE[3] = new Http2Header(":method", "POST");
        STATIC_HEADER_TABLE[4] = new Http2Header(":path", "/");
        STATIC_HEADER_TABLE[5] = new Http2Header(":path", "/index.html");
        STATIC_HEADER_TABLE[6] = new Http2Header(":scheme", "http");
        STATIC_HEADER_TABLE[7] = new Http2Header(":scheme", "https");
        STATIC_HEADER_TABLE[8] = new Http2Header(":status", "200");
        STATIC_HEADER_TABLE[9] = new Http2Header(":status", "204");
        STATIC_HEADER_TABLE[10] = new Http2Header(":status", "206");
        STATIC_HEADER_TABLE[11] = new Http2Header(":status", "304");
        STATIC_HEADER_TABLE[12] = new Http2Header(":status", "400");
        STATIC_HEADER_TABLE[13] = new Http2Header(":status", "404");
        STATIC_HEADER_TABLE[14] = new Http2Header(":status", "500");
        STATIC_HEADER_TABLE[15] = new Http2Header("accept-charset");
        STATIC_HEADER_TABLE[16] = new Http2Header("accept-encoding", "gzip, deflate");
        STATIC_HEADER_TABLE[17] = new Http2Header("accept-language");
        STATIC_HEADER_TABLE[18] = new Http2Header("accept-ranges");
        STATIC_HEADER_TABLE[19] = new Http2Header("accept");
        STATIC_HEADER_TABLE[20] = new Http2Header("access-control-allow-origin");
        STATIC_HEADER_TABLE[21] = new Http2Header("age");
        STATIC_HEADER_TABLE[22] = new Http2Header("allow");
        STATIC_HEADER_TABLE[23] = new Http2Header("authorization");
        STATIC_HEADER_TABLE[24] = new Http2Header("cache-control");
        STATIC_HEADER_TABLE[25] = new Http2Header("content-disposition");
        STATIC_HEADER_TABLE[26] = new Http2Header("content-encoding");
        STATIC_HEADER_TABLE[27] = new Http2Header("content-language");
        STATIC_HEADER_TABLE[28] = new Http2Header("content-length");
        STATIC_HEADER_TABLE[29] = new Http2Header("content-location");
        STATIC_HEADER_TABLE[30] = new Http2Header("content-range");
        STATIC_HEADER_TABLE[31] = new Http2Header("content-type");
        STATIC_HEADER_TABLE[32] = new Http2Header("cookie");
        STATIC_HEADER_TABLE[33] = new Http2Header("date");
        STATIC_HEADER_TABLE[34] = new Http2Header("etag");
        STATIC_HEADER_TABLE[35] = new Http2Header("expect");
        STATIC_HEADER_TABLE[36] = new Http2Header("expires");
        STATIC_HEADER_TABLE[37] = new Http2Header("from");
        STATIC_HEADER_TABLE[38] = new Http2Header("host");
        STATIC_HEADER_TABLE[39] = new Http2Header("if-match");
        STATIC_HEADER_TABLE[40] = new Http2Header("if-modified-since");
        STATIC_HEADER_TABLE[41] = new Http2Header("if-none-match");
        STATIC_HEADER_TABLE[42] = new Http2Header("if-range");
        STATIC_HEADER_TABLE[43] = new Http2Header("if-unmodified-since");
        STATIC_HEADER_TABLE[44] = new Http2Header("last-modified");
        STATIC_HEADER_TABLE[45] = new Http2Header("link");
        STATIC_HEADER_TABLE[46] = new Http2Header("location");
        STATIC_HEADER_TABLE[47] = new Http2Header("max-forwards");
        STATIC_HEADER_TABLE[48] = new Http2Header("proxy-authenticate");
        STATIC_HEADER_TABLE[49] = new Http2Header("proxy-authorization");
        STATIC_HEADER_TABLE[50] = new Http2Header("range");
        STATIC_HEADER_TABLE[51] = new Http2Header("referer");
        STATIC_HEADER_TABLE[52] = new Http2Header("refresh");
        STATIC_HEADER_TABLE[53] = new Http2Header("retry-after");
        STATIC_HEADER_TABLE[54] = new Http2Header("server");
        STATIC_HEADER_TABLE[55] = new Http2Header("set-cookie");
        STATIC_HEADER_TABLE[56] = new Http2Header("strict-transport-security");
        STATIC_HEADER_TABLE[57] = new Http2Header("transfer-encoding");
        STATIC_HEADER_TABLE[58] = new Http2Header("user-agent");
        STATIC_HEADER_TABLE[59] = new Http2Header("vary");
        STATIC_HEADER_TABLE[60] = new Http2Header("via");
        STATIC_HEADER_TABLE[61] = new Http2Header("www-authenticate");
    }

    /** Maximum dynamic table size (SETTINGS_HEADER_TABLE_SIZE) */
    private int maxHeaderSize;
    /** Current total size of all entries */
    private int currentSize;
    /** Circular buffer for dynamic table entries */
    private Http2Header[] table;
    /** Insert position for next entry (circular buffer head) */
    private int newestIdx;
    /** Number of entries in dynamic table */
    private int count;
    /** Bit mask for modulo operation (table.length - 1) */
    private int mask;

    /**
     * Constructs a new HPACK table with the specified maximum size.
     *
     * @param maxHeaderSize the maximum size of the dynamic table in bytes
     */
    public Http2HpackTable(int maxHeaderSize) {
        this.maxHeaderSize = maxHeaderSize;
        this.table = new Http2Header[64];
        this.newestIdx = table.length;
        this.mask = table.length - 1;
    }

    /**
     * Adds a header to the dynamic table.
     * <p>
     * Per RFC 7541 Section 4.4 (Entry Eviction When Adding New Entries):
     * <ul>
     *   <li>Before adding a new entry, evict entries from the end until:
     *       currentSize &lt;= (maxHeaderSize - newEntrySize) OR table is empty</li>
     *   <li>If newEntrySize &gt; maxHeaderSize, the entire table MUST be emptied,
     *       but the entry itself is NOT added (impossible to fit).</li>
     *   <li>This prevents memory exhaustion and mitigates probing attacks (RFC 7.1).</li>
     * </ul>
     *
     * @param header the header to add
     */
    public void addHeader(Http2Header header) {
        if (header.entrySize > maxHeaderSize) {
            clear();
            return;
        }
        while (currentSize + header.entrySize > maxHeaderSize) {
            int oldest = (newestIdx + count - 1) & mask;
            currentSize -= table[oldest].entrySize;
            table[oldest] = null;
            --count;
        }
        if (newestIdx == 0) {
            grow();
        }
        table[--newestIdx] = header;
        currentSize += header.entrySize;
        ++count;
    }

    /**
     * Updates the maximum table size, evicting entries if needed.
     * <p>
     * Per RFC 7541 Section 4.3 (Entry Eviction When Dynamic Table Size Changes):
     * <ul>
     *   <li>When max size decreases, evict oldest entries until currentSize &lt;= newMaxSize</li>
     *   <li>This ensures decoder memory usage stays within bounds</li>
     * </ul>
     *
     * @param maxHeaderSize the new maximum table size in bytes
     */
    public void setMaxHeaderSize(int maxHeaderSize) {
        this.maxHeaderSize = Math.max(maxHeaderSize, 0);
        // RFC 7541 §4.3: Evict from end until size constraint is satisfied
        while (currentSize > this.maxHeaderSize && count > 0) {
            int oldest = (newestIdx + count - 1) & mask;
            currentSize -= table[oldest].entrySize;
            table[oldest] = null;
            --count;
        }
    }

    /**
     * Gets a header by RFC 7541 index.
     * <p>
     * Index mapping (RFC 7541 §2.3.3 - Index Address Space):
     * <ul>
     *   <li>Index 1-61: Static table (predefined headers)</li>
     *   <li>Index 62+: Dynamic table (62 = newest entry, higher = older)</li>
     * </ul>
     *
     * @param index the RFC 7541 index (1-based)
     * @return the header entry
     * @throws Http2HpackException if index is invalid
     */
    public Http2Header getHeader(int index) {
        if (index < 1) {
            // RFC 7541 §2.3.1: both tables are 1-based, index 0 is invalid.
            throw new Http2HpackException("invalid static table index " + index);
        }
        if (index < 62) {
            return STATIC_HEADER_TABLE[index];
        }
        // RFC 7541 §2.3.2: Dynamic table indexing
        int offset = index - 62;
        if (offset >= count) {
            throw new Http2HpackException("invalid dynamic table index " + index);
        }
        int pos = (newestIdx + offset) & mask;
        return table[pos];
    }

    /**
     * Clears all entries from the dynamic table.
     * <p>
     * Called when:
     * <ul>
     *   <li>A single entry exceeds maxHeaderSize (RFC 7541 §4.4)</li>
     *   <li>Explicit table reset is needed</li>
     * </ul>
     */
    private void clear() {
        Arrays.fill(table, null);
        currentSize = 0;
        count = 0;
        newestIdx = table.length;
    }

    /**
     * Doubles the circular buffer capacity.
     * <p>
     * Copies entries to second half [oldLen .. newLen-1], maintains backward insertion order.
     * Note: grow() is only called when newestIdx == 0, entries are at [0..count-1].
     */
    private void grow() {
        int oldLen = table.length;
        int newLen = oldLen << 1;
        Http2Header[] newTable = new Http2Header[newLen];
        System.arraycopy(table, 0, newTable, newestIdx = newLen - count, count);
        table = newTable;
        mask = newLen - 1;
    }
}

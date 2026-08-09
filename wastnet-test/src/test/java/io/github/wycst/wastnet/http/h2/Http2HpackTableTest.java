package io.github.wycst.wastnet.http.h2;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Http2HpackTable}.
 *
 * @author wangyc
 */
public class Http2HpackTableTest {

    // ==================== Static Table ====================

    @Test
    public void testStaticTableEntry0Invalid() {
        Assertions.assertThrows(Http2HpackException.class, () -> new Http2HpackTable(4096).getHeader(0));
    }

    @Test
    public void testStaticTableIndex1() {
        Http2Header header = new Http2HpackTable(4096).getHeader(1);
        Assertions.assertEquals(":authority", header.name);
        Assertions.assertEquals("", header.value);
    }

    @Test
    public void testStaticTableIndex8() {
        Http2Header header = new Http2HpackTable(4096).getHeader(8);
        Assertions.assertEquals(":status", header.name);
        Assertions.assertEquals("200", header.value);
    }

    @Test
    public void testStaticTableIndex31() {
        Http2Header header = new Http2HpackTable(4096).getHeader(31);
        Assertions.assertEquals("content-type", header.name);
        Assertions.assertEquals("", header.value);
    }

    // ==================== Dynamic Table ====================

    @Test
    public void testAddAndGetDynamicHeader() {
        Http2HpackTable table = new Http2HpackTable(4096);
        Http2Header header = new Http2Header("x-custom", "value1");
        table.addHeader(header);
        Assertions.assertSame(header, table.getHeader(62));
    }

    @Test
    public void testAddMultipleAndRetrieveInOrder() {
        Http2HpackTable table = new Http2HpackTable(4096);
        Http2Header h1 = new Http2Header("x-first", "1");
        Http2Header h2 = new Http2Header("x-second", "2");
        table.addHeader(h1);
        table.addHeader(h2);
        Assertions.assertSame(h2, table.getHeader(62));
        Assertions.assertSame(h1, table.getHeader(63));
    }

    @Test
    public void testInvalidDynamicIndexThrows() {
        Assertions.assertThrows(Http2HpackException.class, () -> new Http2HpackTable(4096).getHeader(62));
    }

    // ==================== Eviction ====================

    @Test
    public void testEvictionWhenTableFull() {
        Http2HpackTable table = new Http2HpackTable(75);
        // Each entry: name(6) + value(2) + 32 = 40 bytes
        Http2Header h1 = new Http2Header("x-data", "12");
        Http2Header h2 = new Http2Header("x-data", "34");
        table.addHeader(h1);
        table.addHeader(h2);
        // Evict oldest (h1) because 40 + 40 = 80 > 75
        try {
            table.getHeader(63);
            Assertions.fail("Expected Http2HpackException");
        } catch (Http2HpackException e) { /* expected */ }
        Assertions.assertSame(h2, table.getHeader(62));
    }

    @Test
    public void testEntryTooLargeClearsTable() {
        Http2HpackTable table = new Http2HpackTable(50);
        table.addHeader(new Http2Header("small", "data"));
        table.addHeader(new Http2Header("x-very-large-entry", "some-very-long-value"));
        Assertions.assertEquals(0, countDynamicEntries(table));
    }

    // ==================== setMaxHeaderSize ====================

    @Test
    public void testSetMaxHeaderSizeEvictsOldEntries() {
        Http2HpackTable table = new Http2HpackTable(100);
        // Each entry: name(3) + value(1) + 32 = 36 bytes
        table.addHeader(new Http2Header("x-a", "1"));
        table.addHeader(new Http2Header("x-b", "2"));
        Assertions.assertEquals(2, countDynamicEntries(table));
        // Shrink to only fit 1 entry
        table.setMaxHeaderSize(36);
        Assertions.assertEquals(1, countDynamicEntries(table));
    }

    @Test
    public void testSetMaxHeaderSizeToZero() {
        Http2HpackTable table = new Http2HpackTable(4096);
        table.addHeader(new Http2Header("x-test", "value"));
        table.setMaxHeaderSize(0);
        Assertions.assertEquals(0, countDynamicEntries(table));
    }

    @Test
    public void testSetMaxHeaderSizeNegative() {
        Http2HpackTable table = new Http2HpackTable(4096);
        table.addHeader(new Http2Header("x-test", "value"));
        table.setMaxHeaderSize(-1);
        Assertions.assertEquals(0, countDynamicEntries(table));
    }

    // ==================== Helper ====================

    private static int countDynamicEntries(Http2HpackTable table) {
        int count = 0;
        while (true) {
            try {
                table.getHeader(62 + count);
                count++;
            } catch (Exception e) {
                break;
            }
        }
        return count;
    }
}

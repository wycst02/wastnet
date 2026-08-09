package io.github.wycst.wastnet.http.h2;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Http2Header}.
 *
 * @author wangyc
 */
public class Http2HeaderTest {

    @Test
    public void testConstructorWithNameAndValue() {
        Http2Header header = new Http2Header(":status", "200");
        Assertions.assertEquals(":status", header.name);
        Assertions.assertEquals("200", header.value);
        Assertions.assertEquals(7, header.nameLen);
    }

    @Test
    public void testEntrySizeCalculation() {
        Http2Header header = new Http2Header("content-type", "text/html");
        int expected = "content-type".length() + "text/html".length() + 32;
        Assertions.assertEquals(expected, header.entrySize);
    }

    @Test
    public void testEntrySizeWithNullValue() {
        Http2Header header = new Http2Header("host", null);
        int expected = "host".length() + 0 + 32;
        Assertions.assertEquals(expected, header.entrySize);
    }

    @Test
    public void testConstructorWithExplicitLengths() {
        Http2Header header = new Http2Header("content-length", 14, "1024", 4);
        Assertions.assertEquals("content-length", header.name);
        Assertions.assertEquals("1024", header.value);
        Assertions.assertEquals(14, header.nameLen);
        Assertions.assertEquals(14 + 4 + 32, header.entrySize);
    }

    @Test
    public void testToString() {
        Http2Header header = new Http2Header(":status", "200");
        Assertions.assertEquals(":status: 200", header.toString());
    }

    @Test
    public void testConstructorWithNameOnly() {
        Http2Header header = new Http2Header(":authority");
        Assertions.assertEquals(":authority", header.name);
        Assertions.assertEquals("", header.value);
        Assertions.assertEquals(":authority".length(), header.nameLen);
    }

    @Test
    public void testSelfReturnsThis() {
        Http2Header header = new Http2Header(":status", "200");
        Assertions.assertSame(header, header.self());
    }

    @Test
    public void testToStringCachesResult() {
        Http2Header header = new Http2Header(":status", "200");
        String first = header.toString();
        Assertions.assertEquals(":status: 200", first);
        // second call hits the cached branch
        Assertions.assertEquals(first, header.toString());
    }
}

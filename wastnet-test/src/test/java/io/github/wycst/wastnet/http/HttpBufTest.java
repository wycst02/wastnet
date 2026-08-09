package io.github.wycst.wastnet.http;

import io.github.wycst.wastnet.http.HttpBuf;
import io.github.wycst.wastnet.util.Utils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link HttpBuf}
 *
 * @author wangyc
 */
public class HttpBufTest {

    @Test
    public void testDefaultConstructor() {
        HttpBuf buf = new HttpBuf();
        Assertions.assertTrue(buf.isEmpty());
        Assertions.assertFalse(buf.hasRemaining());
        Assertions.assertEquals(0, buf.size());
    }

    @Test
    public void testWriteByte() {
        HttpBuf buf = new HttpBuf(4);
        buf.write((byte) 'A');
        Assertions.assertEquals(1, buf.size());
        Assertions.assertFalse(buf.isEmpty());
    }

    @Test
    public void testWriteByteArray() {
        HttpBuf buf = new HttpBuf(8);
        int written = buf.write("hello".getBytes());
        Assertions.assertEquals(5, written);
        Assertions.assertEquals(5, buf.size());
    }

    @Test
    public void testWriteByteArrayWithOffset() {
        HttpBuf buf = new HttpBuf(8);
        byte[] data = "hello world".getBytes();
        int written = buf.write(data, 6, 5);
        Assertions.assertEquals(5, written);
        Assertions.assertEquals("world", buf.toString(Utils.ISO_8859_1));
    }

    @Test
    public void testClear() {
        HttpBuf buf = new HttpBuf(8);
        buf.write((byte) 'A');
        buf.write((byte) 'B');
        Assertions.assertEquals(2, buf.size());

        buf.clear();
        Assertions.assertEquals(0, buf.size());
        Assertions.assertTrue(buf.isEmpty());
    }

    @Test
    public void testReset() {
        HttpBuf buf = new HttpBuf(4);
        buf.write("hello world".getBytes());
        Assertions.assertTrue(buf.capacity() > 4); // expanded
        buf.reset();
        Assertions.assertEquals(4, buf.capacity()); // back to initial
        Assertions.assertEquals(0, buf.size());
    }

    @Test
    public void testToBytes() {
        HttpBuf buf = new HttpBuf(8);
        buf.write("test".getBytes());
        byte[] bytes = buf.toBytes();
        Assertions.assertArrayEquals("test".getBytes(), bytes);
    }

    @Test
    public void testLastByte() {
        HttpBuf buf = new HttpBuf(8);
        buf.write("abc".getBytes());
        Assertions.assertEquals((byte) 'c', buf.lastByte());
    }

    @Test
    public void testLastByteEmpty() {
        HttpBuf buf = new HttpBuf(8);
        Assertions.assertEquals((byte) 0, buf.lastByte());
    }

    @Test
    public void testFirstByte() {
        HttpBuf buf = new HttpBuf(8);
        buf.write("abc".getBytes());
        Assertions.assertEquals((byte) 'a', buf.firstByte());
    }

    @Test
    public void testDeleteHead() {
        HttpBuf buf = new HttpBuf(8);
        buf.write("hello".getBytes());
        int remaining = buf.deleteHead(2);
        Assertions.assertEquals(3, remaining);
        Assertions.assertEquals(3, buf.size());
        Assertions.assertEquals("llo", buf.toString(Utils.ISO_8859_1));
    }

    @Test
    public void testDeleteTail() {
        HttpBuf buf = new HttpBuf(8);
        buf.write("hello".getBytes());
        buf.deleteTail(2);
        Assertions.assertEquals(3, buf.size());
        Assertions.assertEquals("hel", buf.toString(Utils.ISO_8859_1));
    }

    @Test
    public void testDeleteHeadOutOfBounds() {
        HttpBuf buf = new HttpBuf(8);
        buf.write("hi".getBytes());
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> buf.deleteHead(10));
    }

    @Test
    public void testDeleteTailOutOfBounds() {
        HttpBuf buf = new HttpBuf(8);
        buf.write("hi".getBytes());
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> buf.deleteTail(10));
    }

    @Test
    public void testCapacity() {
        HttpBuf buf = new HttpBuf(16);
        Assertions.assertEquals(16, buf.capacity());
    }

    @Test
    public void testFactoryMethods() {
        Assertions.assertNotNull(HttpBuf.empty());
        Assertions.assertEquals(0, HttpBuf.empty().capacity());

        HttpBuf buf32 = HttpBuf.of(32);
        Assertions.assertEquals(32, buf32.capacity());

        HttpBuf buf64 = HttpBuf.of(64, 128);
        Assertions.assertEquals(64, buf64.capacity());
        Assertions.assertEquals(128, buf64.getMaxCapacity());
    }


    @Test
    public void testToStringEncoding() {
        HttpBuf buf = new HttpBuf(8);
        buf.write("hello".getBytes());
        Assertions.assertEquals("hello", buf.toString(Utils.ISO_8859_1));
    }

    @Test
    public void testBackOne() {
        HttpBuf buf = new HttpBuf(8);
        buf.write("hello".getBytes());
        buf.backOne();
        Assertions.assertEquals(4, buf.size());
        Assertions.assertEquals("hell", buf.toString(Utils.ISO_8859_1));
    }

    @Test
    public void testReplace() {
        HttpBuf buf = new HttpBuf(8);
        buf.write("old".getBytes());
        buf.replace("new".getBytes());
        Assertions.assertEquals("new", buf.toString(Utils.ISO_8859_1));
        Assertions.assertEquals(3, buf.size());
    }

    @Test
    public void testReplaceNull() {
        HttpBuf buf = new HttpBuf(8);
        buf.write("data".getBytes());
        buf.replace(null);
        Assertions.assertTrue(buf.isEmpty());
    }

    @Test
    public void testExtra() {
        HttpBuf buf = new HttpBuf(8);
        Assertions.assertSame(buf, buf.extra(42));
        Assertions.assertEquals(42, buf.extra());
    }

    @Test
    public void testToStringFallback() {
        HttpBuf buf = new HttpBuf(10000);
        byte[] largeData = new byte[8193];
        for (int i = 0; i < largeData.length; ++i) {
            largeData[i] = (byte) ('a' + (i % 26));
        }
        buf.write(largeData);
        // Should not throw, just calls super.toString() for large buffers
        Assertions.assertNotNull(buf.toString());
    }

    @Test
    public void testMaxCapacityLimit() {
        HttpBuf buf = new HttpBuf(4, 8);
        byte[] data = "this is a long string that exceeds max capacity".getBytes();
        int written = buf.write(data);
        Assertions.assertTrue(written <= 8);
        Assertions.assertTrue(buf.size() <= 8);
    }

    @Test
    public void testCompactMovesData() {
        HttpBuf buf = new HttpBuf(16);
        buf.write("hello".getBytes());
        buf.deleteHead(2);          // begin=2, count=3
        Assertions.assertEquals("llo", buf.toISO_8859_1_string());
        buf.compact();    // should move "llo" to offset 0
        Assertions.assertEquals(0, buf.getBegin());
        Assertions.assertEquals("llo", buf.toISO_8859_1_string());
    }

    @Test
    public void testCompactNoOpWhenBeginZero() {
        HttpBuf buf = new HttpBuf(16);
        buf.write("hello".getBytes());
        buf.compact();
        Assertions.assertEquals(0, buf.getBegin());
    }

    @Test
    public void testSetBegin() {
        HttpBuf buf = new HttpBuf(16);
        buf.write("hello".getBytes());
        buf.setBegin(2);
        Assertions.assertEquals(2, buf.getBegin());
        // After setting begin, read from the raw buffer at new offset via byteBuffer
        java.nio.ByteBuffer bb = buf.byteBuffer();
        byte[] data = new byte[bb.remaining()];
        bb.get(data);
        Assertions.assertEquals('l', data[0]); // first valid byte after offset 2
    }

    @Test
    public void testFactoryCreate() {
        HttpBuf buf = HttpBuf.create();
        Assertions.assertNotNull(buf);
        Assertions.assertTrue(buf.capacity() >= 64);
        Assertions.assertTrue(buf.isEmpty());
    }

    @Test
    public void testReplaceWithOffsetAndLen() {
        HttpBuf buf = new HttpBuf(8);
        buf.write("old".getBytes());
        byte[] data = "prefix_middle_suffix".getBytes();
        buf.replace(data, 7, 6);
        Assertions.assertEquals("middle", buf.toISO_8859_1_string());
        Assertions.assertEquals(6, buf.size());
    }

    @Test
    public void testReplaceWithOffsetNullClears() {
        HttpBuf buf = new HttpBuf(8);
        buf.write("data".getBytes());
        buf.replace(null, 0, 0);
        Assertions.assertTrue(buf.isEmpty());
    }

    @Test
    public void testReplaceWithOffsetInvalidThrows() {
        HttpBuf buf = new HttpBuf(8);
        byte[] data = "short".getBytes();
        Assertions.assertThrows(IndexOutOfBoundsException.class,
                () -> buf.replace(data, 10, 1));
    }

    @Test
    public void testByteBuffer() {
        HttpBuf buf = new HttpBuf(8);
        buf.write("test".getBytes());
        java.nio.ByteBuffer bb = buf.byteBuffer();
        Assertions.assertEquals(4, bb.remaining());
        byte[] dst = new byte[4];
        bb.get(dst);
        Assertions.assertArrayEquals("test".getBytes(), dst);
    }

    @Test
    public void testWriteISO_8859_1String() {
        HttpBuf buf = new HttpBuf(16);
        buf.writeISO_8859_1String("hello");
        Assertions.assertEquals(5, buf.size());
        Assertions.assertEquals("hello", buf.toISO_8859_1_string());
    }

    @Test
    public void testToISO8859WithOffset() {
        HttpBuf buf = new HttpBuf(16);
        buf.write("abcdef".getBytes());
        String part = buf.toISO_8859_1_string(2, 3);
        Assertions.assertEquals("cde", part);
    }

    @Test
    public void testToStringWithOffsetAndCharset() throws Exception {
        HttpBuf buf = new HttpBuf(16);
        buf.write("你好".getBytes("UTF-8"));
        String part = buf.toString(0, 3, java.nio.charset.StandardCharsets.UTF_8);
        Assertions.assertEquals("你", part);
    }

    @Test
    public void testWrap() {
        byte[] data = "wrapped".getBytes();
        HttpBuf buf = HttpBuf.wrap(data, 0, data.length);
        Assertions.assertEquals("wrapped", buf.toISO_8859_1_string());
        Assertions.assertEquals(data.length, buf.size());
    }

    // ==================== Additional branches ====================

    @Test
    public void testCompactWhenBeginPositiveButCountZero() {
        // begin > 0 and count == 0 → compact skips arraycopy
        HttpBuf buf = new HttpBuf(8);
        buf.write("hi".getBytes());
        buf.deleteHead(2); // begin=2, count=0
        buf.compact();
        Assertions.assertEquals(0, buf.getBegin());
    }

    @Test
    public void testIncrementCapacityCompactAndExpand() {
        // begin < increment & required > buf.length → compact + expand
        HttpBuf buf = new HttpBuf(4, 16);
        buf.write("ABCD".getBytes()); // count=4, begin=0
        buf.deleteHead(1);            // begin=1, count=3
        buf.write("EFGH".getBytes()); // required=1+3+4=8 > 4, begin=1 < 4 → expand
        Assertions.assertEquals(7, buf.size());
        Assertions.assertEquals("BCDEFGH", buf.toISO_8859_1_string());
    }

    @Test
    public void testIncrementCapacityCompactOnly() {
        // begin >= increment → just compact, no expand needed
        HttpBuf buf = new HttpBuf(8, 16);
        buf.write("ABCDEFGH".getBytes()); // count=8, begin=0
        buf.deleteHead(6);                // begin=6, count=2
        // Now write "XYZ": writableSize=min(16-2,3)=3, increment=3
        // required=6+2+3=11 > buf.length=8, enough=6>=3=true → compact only
        buf.write("XYZ".getBytes());
        Assertions.assertEquals(5, buf.size());
        Assertions.assertEquals("GHXYZ", buf.toISO_8859_1_string());
    }

    @Test
    public void testIncrementCapacityMaxCapacityReached() {
        // maxCapacity == buf.length → can't expand
        HttpBuf buf = new HttpBuf(8, 8);
        buf.write("ABCDEFG".getBytes()); // count=7, begin=0
        buf.deleteHead(1);               // begin=1, count=6
        // Write "HIJ": writableSize=min(8-6,3)=2, increment=2
        // required=1+6+2=9 > 8, enough=1>=2=false, compact, new=min(9+4=13,8)=8
        // maxCapacity(8)==buf.length(8) → return without expand
        int written = buf.write("HIJ".getBytes());
        Assertions.assertEquals(2, written); // only 2 of 3 written
        Assertions.assertEquals(8, buf.size());
        Assertions.assertEquals(8, buf.capacity());
    }

    @Test
    public void testToISO8859NoTrim() {
        HttpBuf buf = new HttpBuf(16);
        buf.write("  data  ".getBytes());
        Assertions.assertEquals("  data  ", buf.toISO_8859_1_string(false));
    }

    @Test
    public void testToISO8859WithTrim() {
        HttpBuf buf = new HttpBuf(16);
        buf.write("  hello world  ".getBytes());
        Assertions.assertEquals("hello world", buf.toISO_8859_1_string(true));
    }

    @Test
    public void testToISO8859TrimAllSpaces() {
        HttpBuf buf = new HttpBuf(16);
        buf.write("   ".getBytes());
        Assertions.assertEquals("", buf.toISO_8859_1_string(true));
    }

    @Test
    public void testDeleteTailNegativeThrows() {
        HttpBuf buf = new HttpBuf(8);
        buf.write("data".getBytes());
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> buf.deleteTail(-1));
    }

    @Test
    public void testDeleteHeadNegativeThrows() {
        HttpBuf buf = new HttpBuf(8);
        buf.write("data".getBytes());
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> buf.deleteHead(-1));
    }

    @Test
    public void testReplaceWithOffsetNegativeThrows() {
        HttpBuf buf = new HttpBuf(8);
        byte[] data = "test".getBytes();
        Assertions.assertThrows(IndexOutOfBoundsException.class,
                () -> buf.replace(data, -1, 2));
    }

    @Test
    public void testReplaceWithOffsetLenNegativeThrows() {
        HttpBuf buf = new HttpBuf(8);
        byte[] data = "test".getBytes();
        Assertions.assertThrows(IndexOutOfBoundsException.class,
                () -> buf.replace(data, 0, -1));
    }
}

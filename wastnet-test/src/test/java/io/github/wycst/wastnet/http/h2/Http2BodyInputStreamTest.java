package io.github.wycst.wastnet.http.h2;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;

/**
 * Unit tests for {@link Http2BodyInputStream}.
 * <p>
 * Note: Must call endStream() before reading beyond initial buffer data,
 * otherwise read() will try to read from ChannelContext and hang on mocks.
 *
 * @author wangyc
 */
public class Http2BodyInputStreamTest {

    @Test
    public void testEMPTYSingleton() {
        Assertions.assertNotNull(Http2BodyInputStream.EMPTY);
    }

    @Test
    public void testReadFromBodyData() throws Exception {
        byte[] data = "hello".getBytes();
        Http2BodyInputStream is = new Http2BodyInputStream(data, null);
        int b = is.read();
        Assertions.assertEquals('h', b);
        b = is.read();
        Assertions.assertEquals('e', b);
    }

    @Test
    public void testReadBuffer() throws Exception {
        byte[] data = "hello world".getBytes();
        Http2BodyInputStream is = new Http2BodyInputStream(data, null);
        byte[] buf = new byte[5];
        int read = is.read(buf, 0, 5);
        Assertions.assertEquals(5, read);
        Assertions.assertEquals("hello", new String(buf, 0, 5));
    }

    @Test
    public void testReadBufferPartial() throws Exception {
        byte[] data = "abc".getBytes();
        Http2BodyInputStream is = new Http2BodyInputStream(data, null);
        byte[] buf = new byte[10];
        int read = is.read(buf, 0, 10);
        Assertions.assertEquals(3, read);
    }

    @Test
    public void testReadReturnsMinusOneAfterEndStream() throws Exception {
        byte[] data = "x".getBytes();
        Http2BodyInputStream is = new Http2BodyInputStream(data, null);
        is.read(); // consume 'x'
        is.endStream();
        int result = is.read();
        Assertions.assertEquals(-1, result);
    }

    @Test
    public void testFeedWhenBufferFullRejected() throws Exception {
        byte[] largeData = new byte[65535 * 2]; // Beyond internal capacity
        Http2BodyInputStream is = new Http2BodyInputStream(largeData, null);

        boolean accepted = is.feed("extra".getBytes(), 0, 5);
        Assertions.assertFalse(accepted);
    }

    @Test
    public void testGetConsumedAfterRead() throws Exception {
        byte[] data = "test".getBytes();
        Http2BodyInputStream is = new Http2BodyInputStream(data, null);
        is.read(new byte[2], 0, 2);
        Assertions.assertEquals(2, is.getConsumed());
    }

    @Test
    public void testEndStreamOnEmptyBuffer() throws Exception {
        Http2BodyInputStream is = new Http2BodyInputStream("data".getBytes(), null);
        // Read all data first
        byte[] buf = new byte[4];
        is.read(buf, 0, 4);
        is.endStream();
        int result = is.read();
        Assertions.assertEquals(-1, result);
    }

    @Test
    public void testAvailableThrows() throws Exception {
        byte[] data = "data".getBytes();
        Http2BodyInputStream is = new Http2BodyInputStream(data, null);
        Assertions.assertThrows(IOException.class, () -> is.available());
    }

    @Test
    public void testEmptyInputStreamReadReturnsMinusOne() throws Exception {
        Assertions.assertEquals(-1, Http2BodyInputStream.EMPTY.read());
    }

    @Test
    public void testEmptyInputStreamAvailableThrows() {
        try {
            Http2BodyInputStream.EMPTY.available();
            Assertions.fail("Expected IOException");
        } catch (IOException e) {
            // expected
        }
    }

    @Test
    public void testReadMultipleBytesSequentially() throws Exception {
        byte[] data = "abcd".getBytes();
        Http2BodyInputStream is = new Http2BodyInputStream(data, null);
        Assertions.assertEquals('a', is.read());
        Assertions.assertEquals('b', is.read());
        Assertions.assertEquals('c', is.read());
        Assertions.assertEquals('d', is.read());
    }

    @Test
    public void testEndStreamOnEmptyStream() throws Exception {
        Http2BodyInputStream is = new Http2BodyInputStream(new byte[0], null);
        is.endStream();
        Assertions.assertEquals(-1, is.read());
    }

    // ==================== read validation branches ====================

    @Test
    public void testReadNullBuffer() {
        Http2BodyInputStream is = new Http2BodyInputStream("x".getBytes(), null);
        Assertions.assertThrows(NullPointerException.class, () -> is.read(null, 0, 1));
    }

    @Test
    public void testReadNegativeOffset() {
        Http2BodyInputStream is = new Http2BodyInputStream("x".getBytes(), null);
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> is.read(new byte[1], -1, 1));
    }

    @Test
    public void testReadZeroLength() throws Exception {
        Http2BodyInputStream is = new Http2BodyInputStream("x".getBytes(), null);
        Assertions.assertEquals(0, is.read(new byte[1], 0, 0));
    }

    @Test
    public void testEmptyCapacityReturnsMinusOne() throws Exception {
        // EMPTY has capacity = 0, read should return -1 immediately
        Assertions.assertEquals(-1, Http2BodyInputStream.EMPTY.read(new byte[1], 0, 1));
    }

    // ==================== feed branches ====================

    @Test
    public void testFeedZeroLength() {
        Http2BodyInputStream is = new Http2BodyInputStream("x".getBytes(), null);
        Assertions.assertTrue(is.feed(new byte[5], 0, 0));
    }

    @Test
    public void testFeedAfterEndedReturnsTrue() {
        Http2BodyInputStream is = new Http2BodyInputStream("x".getBytes(), null);
        is.endStream();
        Assertions.assertTrue(is.feed(new byte[5], 0, 5));
    }

    // ==================== wrapped read / wrapping copy ====================

    @Test
    public void testReadWithWrappedCopy() throws Exception {
        // Position feed at end of buffer to force wrapped read next time.
        // Initial data fills most of capacity, then read to create wrap.
        byte[] bigData = new byte[65535];
        Arrays.fill(bigData, (byte) 'A');
        Http2BodyInputStream is = new Http2BodyInputStream(bigData, null);
        byte[] buf = new byte[100];
        int n = is.read(buf, 0, 100);
        Assertions.assertEquals(100, n);
    }

    @Test
    public void testFeedLinearCopy() throws Exception {
        byte[] data = new byte[100];
        Arrays.fill(data, (byte) 'X');
        Http2BodyInputStream is = new Http2BodyInputStream(data, null);
        // Read all initial data
        byte[] readBuf = new byte[200];
        int n = is.read(readBuf, 0, 100);
        Assertions.assertEquals(100, n);
        // Feed more data (linear copy since feedPos advances linearly from 0)
        byte[] moreData = "hello".getBytes();
        boolean ok = is.feed(moreData, 0, moreData.length);
        Assertions.assertTrue(ok);
        // Read the fed data
        n = is.read(readBuf, 0, moreData.length);
        Assertions.assertEquals(moreData.length, n);
        Assertions.assertEquals("hello", new String(readBuf, 0, n));
    }

    // ==================== branch coverage: validation edge cases ====================

    @Test
    public void testReadNegativeLength() {
        Http2BodyInputStream is = new Http2BodyInputStream("x".getBytes(), null);
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> is.read(new byte[1], 0, -1));
    }

    @Test
    public void testReadLengthTooLarge() {
        Http2BodyInputStream is = new Http2BodyInputStream("x".getBytes(), null);
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> is.read(new byte[1], 0, 2));
    }

    // ==================== circular buffer wrap branches ====================

    @Test
    public void testWrappedReadAndFeed() throws Exception {
        // Small 10-byte circular buffer for deterministic wrap behavior
        byte[] initData = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        Http2BodyInputStream is = new Http2BodyInputStream(initData, null);

        // Step 1: Read 6 bytes → bodyPos=6, full=false, feedPos=0
        //         feed free calc uses bodyPos(6) > feedPos(0) → free = pos - feed = 6
        is.read(new byte[6], 0, 6);

        // Step 2: Feed 6 bytes → overwrites [0..5], newFeed=6, bodyPos=6 → full=true
        is.feed(new byte[]{10, 11, 12, 13, 14, 15}, 0, 6);

        // Step 3: Read 7 bytes with full=true, pos=6, 6+7=13>10 → WRAPPED READ (L120 false)
        //         firstSeg=10-6=4, copy [6,7,8,9] then [10,11,12]
        //         newPos=6+7=13-10=3
        byte[] out = new byte[7];
        int n = is.read(out, 0, 7);
        Assertions.assertEquals(7, n);
        Assertions.assertArrayEquals(new byte[]{6, 7, 8, 9, 10, 11, 12}, out);

        // Step 4: Feed 7 bytes at feedPos=6, untilEnd=4, 7>4 → WRAPPED FEED (L198 false)
        //         copy [6..9] then [0..2], newFeed=6+7=13-10=3, bodyPos=3 → full=true (L211 true)
        is.feed(new byte[]{20, 21, 22, 23, 24, 25, 26}, 0, 7);

        // Step 5: Read 10 bytes → full=true, pos=3 → wrapped read
        //         Result: [13,14,15,20,21,22,23,24,25,26]
        byte[] out2 = new byte[10];
        n = is.read(out2, 0, 10);
        Assertions.assertEquals(10, n);
        Assertions.assertArrayEquals(new byte[]{13, 14, 15, 20, 21, 22, 23, 24, 25, 26}, out2);
    }

    // ==================== wait / notify (multi-threaded coverage) ====================

    @Test
    public void testReadBlocksThenEnded() throws Exception {
        byte[] data = new byte[10];
        Http2BodyInputStream is = new Http2BodyInputStream(data, null);

        // Read all data to empty the buffer (bodyPos=0, feedPos=0, full=false)
        is.read(new byte[10], 0, 10);

        // Reader thread: read() will see avail=0, !ended, enter synchronized block
        Thread reader = new Thread(() -> {
            try {
                int n = is.read(new byte[1], 0, 1);
                Assertions.assertEquals(-1, n);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, "test-reader");
        reader.start();

        // Give reader time to enter wait()
        Thread.sleep(200);
        // endStream() wakes up the reader via notifyAll()
        is.endStream();
        reader.join(3000);
        Assertions.assertFalse(reader.isAlive(), "reader thread should have completed");
    }
}

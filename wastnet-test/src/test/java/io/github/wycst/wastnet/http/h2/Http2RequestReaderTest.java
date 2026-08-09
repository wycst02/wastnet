package io.github.wycst.wastnet.http.h2;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Http2ServerReader}.
 *
 * @author wangyc
 */
public class Http2RequestReaderTest {

    @Test
    public void testValidatePrefaceWithCorrectMagic() {
        byte[] magic = {0x50, 0x52, 0x49, 0x20, 0x2A, 0x20, 0x48, 0x54, 0x54, 0x50, 0x2F, 0x32, 0x2E, 0x30, 0x0D, 0x0A, 0x0D, 0x0A, 0x53, 0x4D, 0x0D, 0x0A, 0x0D, 0x0A};
        Assertions.assertTrue(Http2ServerReader.validatePreface(magic));
    }

    @Test
    public void testValidatePrefaceWithWrongBytes() {
        byte[] wrong = new byte[24];
        Assertions.assertFalse(Http2ServerReader.validatePreface(wrong));
    }

    @Test
    public void testValidatePrefaceWithNull() {
        Assertions.assertThrows(NullPointerException.class, () -> Http2ServerReader.validatePreface(null));
    }

    @Test
    public void testValidatePrefaceWithEmpty() {
        Assertions.assertFalse(Http2ServerReader.validatePreface(new byte[0]));
    }

    @Test
    public void testValidatePrefaceWithShortData() {
        Assertions.assertFalse(Http2ServerReader.validatePreface(new byte[10]));
    }

    @Test
    public void testReadUInt24() {
        byte[] data = {0x00, 0x00, 0x0c};
        Assertions.assertEquals(12, Http2ServerReader.readUInt24(data, 0));
    }

    @Test
    public void testReadUInt24MaxValue() {
        byte[] data = {(byte) 0xff, (byte) 0xff, (byte) 0xff};
        Assertions.assertEquals(0xffffff, Http2ServerReader.readUInt24(data, 0));
    }

    @Test
    public void testReadUInt24WithOffset() {
        byte[] data = {0x00, 0x00, 0x00, (byte) 0x80, 0x00, 0x01};
        Assertions.assertEquals(0x800001, Http2ServerReader.readUInt24(data, 3));
    }

    @Test
    public void testReadInt32() {
        byte[] data = {0x00, 0x00, 0x00, 0x01};
        Assertions.assertEquals(1, Http2ServerReader.readInt32(data, 0));
    }

    @Test
    public void testReadInt32MaxStreamId() {
        byte[] data = {0x7f, (byte) 0xff, (byte) 0xff, (byte) 0xff};
        Assertions.assertEquals(0x7fffffff, Http2ServerReader.readInt32(data, 0));
    }

    @Test
    public void testReadUInt16() {
        byte[] data = {0x00, 0x64};
        Assertions.assertEquals(100, Http2ServerReader.readUInt16(data, 0));
    }

    @Test
    public void testReadUInt16MaxValue() {
        byte[] data = {(byte) 0xff, (byte) 0xff};
        Assertions.assertEquals(65535, Http2ServerReader.readUInt16(data, 0));
    }
}

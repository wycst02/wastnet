package io.github.wycst.wastnet.http;

import io.github.wycst.wastnet.http.HttpUriDecoder;
import io.github.wycst.wastnet.util.Utils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link HttpUriDecoder}
 *
 * @author wangyc
 */
public class HttpUriDecoderTest {

    @Test
    public void testDecodeSimplePath() {
        HttpUriDecoder decoder = new HttpUriDecoder(false);
        decoder.codec("/index.html".getBytes());
        decoder.endCodec();
        Assertions.assertEquals("/index.html", decoder.getUri());
        Assertions.assertTrue(decoder.getParameters().isEmpty());
    }

    @Test
    public void testDecodeWithQueryString() {
        HttpUriDecoder decoder = new HttpUriDecoder(false);
        decoder.codec("/search?q=hello".getBytes());
        decoder.endCodec();
        Assertions.assertEquals("/search", decoder.getUri());
        Assertions.assertEquals("hello", decoder.getParameters().get("q").get(0));
    }

    @Test
    public void testDecodeMultipleParams() {
        HttpUriDecoder decoder = new HttpUriDecoder(false);
        decoder.codec("/path?a=1&b=2&c=3".getBytes());
        decoder.endCodec();
        Assertions.assertEquals("/path", decoder.getUri());
        Assertions.assertEquals("1", decoder.getParameters().get("a").get(0));
        Assertions.assertEquals("2", decoder.getParameters().get("b").get(0));
        Assertions.assertEquals("3", decoder.getParameters().get("c").get(0));
    }

    @Test
    public void testDecodeRepeatedParams() {
        HttpUriDecoder decoder = new HttpUriDecoder(false);
        decoder.codec("/path?key=a&key=b&key=c".getBytes());
        decoder.endCodec();
        Assertions.assertEquals(3, decoder.getParameters().get("key").size());
        Assertions.assertEquals("a", decoder.getParameters().get("key").get(0));
        Assertions.assertEquals("b", decoder.getParameters().get("key").get(1));
        Assertions.assertEquals("c", decoder.getParameters().get("key").get(2));
    }

    @Test
    public void testDecodePercentEncoded() {
        HttpUriDecoder decoder = new HttpUriDecoder(false);
        decoder.codec("/path%20with%20spaces".getBytes());
        decoder.endCodec();
        Assertions.assertEquals("/path with spaces", decoder.getUri());
    }

    @Test
    public void testDecodePercentEncodedUTF8() {
        HttpUriDecoder decoder = new HttpUriDecoder(false);
        byte[] data = "/%E4%B8%AD%E6%96%87".getBytes(Utils.UTF_8);
        decoder.codec(data);
        decoder.endCodec();
        Assertions.assertEquals("/中文", decoder.getUri());
    }

    @Test
    public void testDecodePlusAsSpace() {
        HttpUriDecoder decoder = new HttpUriDecoder(false);
        decoder.codec("/path?q=hello+world".getBytes());
        decoder.endCodec();
        Assertions.assertEquals("/path", decoder.getUri());
        Assertions.assertEquals("hello world", decoder.getParameters().get("q").get(0));
    }

    @Test
    public void testDecodeFragmentIgnored() {
        HttpUriDecoder decoder = new HttpUriDecoder(false);
        decoder.codec("/page#section".getBytes());
        decoder.endCodec();
        Assertions.assertEquals("/page", decoder.getUri());
    }

    @Test
    public void testDecodeEmptyQuery() {
        HttpUriDecoder decoder = new HttpUriDecoder(false);
        decoder.codec("/path?".getBytes());
        decoder.endCodec();
        Assertions.assertEquals("/path", decoder.getUri());
    }

    @Test
    public void testDecodeParamWithoutValue() {
        HttpUriDecoder decoder = new HttpUriDecoder(false);
        decoder.codec("/path?flag".getBytes());
        decoder.endCodec();
        Assertions.assertNull(decoder.getParameters().get("flag").get(0));
    }

    @Test
    public void testDecodeParamWithEmptyValue() {
        HttpUriDecoder decoder = new HttpUriDecoder(false);
        decoder.codec("/path?key=".getBytes());
        decoder.endCodec();
        Assertions.assertEquals("", decoder.getParameters().get("key").get(0));
    }

    @Test
    public void testReset() {
        HttpUriDecoder decoder = new HttpUriDecoder(false);
        decoder.codec("/first".getBytes());
        decoder.endCodec();
        Assertions.assertEquals("/first", decoder.getUri());

        decoder.reset();
        decoder.codec("/second".getBytes());
        decoder.endCodec();
        Assertions.assertEquals("/second", decoder.getUri());
    }

    @Test
    public void testFormUrlencodedMode() {
        HttpUriDecoder decoder = new HttpUriDecoder(false, true);
        decoder.codec("name=John&age=30".getBytes());
        decoder.endCodec();
        Assertions.assertEquals("John", decoder.getParameters().get("name").get(0));
        Assertions.assertEquals("30", decoder.getParameters().get("age").get(0));
    }

    @Test
    public void testStrictModeInvalidEscape() {
        HttpUriDecoder decoder = new HttpUriDecoder(true);
        try {
            decoder.codec("/path%ZZmore".getBytes());
            decoder.endCodec();
            Assertions.fail("Expected IllegalArgumentException in strict mode");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    @Test
    public void testNonStrictModeInvalidEscape() {
        HttpUriDecoder decoder = new HttpUriDecoder(false);
        decoder.codec("/path%ZZmore".getBytes());
        decoder.endCodec();
        Assertions.assertTrue(decoder.getUri().contains("%"));
    }

    @Test
    public void testIncrementalCodec() {
        HttpUriDecoder decoder = new HttpUriDecoder(false);
        decoder.codec("/pa".getBytes());
        decoder.codec("th?q".getBytes());
        decoder.codec("=hello".getBytes());
        decoder.endCodec();
        Assertions.assertEquals("/path", decoder.getUri());
        Assertions.assertEquals("hello", decoder.getParameters().get("q").get(0));
    }
}

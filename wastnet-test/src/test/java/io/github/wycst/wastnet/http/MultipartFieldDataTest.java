package io.github.wycst.wastnet.http;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Unit tests for {@link MultipartFieldData}.
 *
 * @author wangyc
 */
public class MultipartFieldDataTest {

    private static final Charset UTF_8 = StandardCharsets.UTF_8;

    private static HttpBuf wrapBuf(String s) {
        byte[] b = s.getBytes(UTF_8);
        return HttpBuf.wrap(b, 0, b.length);
    }

    @Test
    public void testTextFieldCreation() {
        MultipartFieldData field = new MultipartFieldData("username", null, null,
                "john".getBytes(), UTF_8);
        Assertions.assertEquals("username", field.getName());
        Assertions.assertNull(field.getFileName());
        Assertions.assertNull(field.getContentType());
        Assertions.assertFalse(field.isFile());
        Assertions.assertFalse(field.isTempFile());
    }

    @Test
    public void testFileFieldCreation() {
        HttpBuf fileName = wrapBuf("test.txt");
        HttpBuf contentType = wrapBuf("text/plain");
        MultipartFieldData field = new MultipartFieldData("upload", fileName, contentType,
                "hello world".getBytes(), UTF_8);
        Assertions.assertEquals("upload", field.getName());
        Assertions.assertEquals("test.txt", field.getFileName());
        Assertions.assertEquals("text/plain", field.getContentType());
        Assertions.assertTrue(field.isFile());
    }

    @Test
    public void testGetData() {
        byte[] data = "hello world".getBytes();
        MultipartFieldData field = new MultipartFieldData("data", null, null,
                data, UTF_8);
        Assertions.assertArrayEquals(data, field.getData());
    }

    @Test
    public void testGetDataAsString() {
        MultipartFieldData field = new MultipartFieldData("name", null, null,
                "test value".getBytes(UTF_8), UTF_8);
        Assertions.assertEquals("test value", field.getDataAsString());
        Assertions.assertEquals("test value", field.getDataAsString(UTF_8));
    }

    @Test
    public void testGetInputStream() throws Exception {
        byte[] data = "stream data".getBytes();
        MultipartFieldData field = new MultipartFieldData("file", null, null,
                data, UTF_8);
        InputStream is = field.getInputStream();
        Assertions.assertNotNull(is);
        byte[] readData = new byte[data.length];
        is.read(readData);
        Assertions.assertArrayEquals(data, readData);
    }

    @Test
    public void testTransferToThrowsForNonFileField() throws Exception {
        MultipartFieldData field = new MultipartFieldData("name", null, null,
                "value".getBytes(), UTF_8);
        Assertions.assertThrows(UnsupportedOperationException.class, () -> field.transferTo(new File("test.txt")));
    }

    @Test
    public void testTransferToForFileField() throws Exception {
        HttpBuf fileName = wrapBuf("test.txt");
        byte[] data = "file content".getBytes();
        MultipartFieldData field = new MultipartFieldData("file", fileName, null,
                data, UTF_8);
        File tempFile = File.createTempFile("test-", ".tmp");
        try {
            field.transferTo(tempFile);
            Assertions.assertTrue(tempFile.exists());
            Assertions.assertEquals("file content", new String(java.nio.file.Files.readAllBytes(tempFile.toPath())));
        } finally {
            tempFile.delete();
        }
    }

    @Test
    public void testTransferToWithAppend() throws Exception {
        HttpBuf fileName = wrapBuf("test.txt");
        MultipartFieldData field = new MultipartFieldData("file", fileName, null,
                " appended".getBytes(), UTF_8);
        File tempFile = File.createTempFile("test-", ".tmp");
        try {
            java.nio.file.Files.write(tempFile.toPath(), "initial".getBytes());
            field.transferTo(tempFile, true);
            Assertions.assertEquals("initial appended", new String(java.nio.file.Files.readAllBytes(tempFile.toPath())));
        } finally {
            tempFile.delete();
        }
    }

    @Test
    public void testContentTypeWithHttpBuf() {
        HttpBuf ctBuf = wrapBuf("image/png");
        MultipartFieldData field = new MultipartFieldData("img", null, ctBuf,
                new byte[]{1, 2, 3}, UTF_8);
        Assertions.assertEquals("image/png", field.getContentType());
    }

    @Test
    public void testGetDataWithHttpBufConstructor() {
        HttpBuf data = wrapBuf("buf data");
        MultipartFieldData field = new MultipartFieldData("field", null, null, data, UTF_8);
        Assertions.assertArrayEquals("buf data".getBytes(), field.getData());
    }

    @Test
    public void testSize() {
        byte[] data = "hello world".getBytes();
        MultipartFieldData field = new MultipartFieldData("data", null, null, data, UTF_8);
        Assertions.assertEquals(data.length, field.size());
    }

    @Test
    public void testSizeWithHttpBufConstructor() {
        HttpBuf data = wrapBuf("buf size data");
        MultipartFieldData field = new MultipartFieldData("field", null, null, data, UTF_8);
        Assertions.assertEquals("buf size data".length(), field.size());
    }

    @Test
    public void testTransferToThrowsWhenTargetUnwritable() throws Exception {
        // 非空 fileName 使 isFile() 为 true，绕过 UnsupportedOperationException 保护
        HttpBuf fileName = wrapBuf("test.txt");
        MultipartFieldData field = new MultipartFieldData("file", fileName, null,
                "data".getBytes(), UTF_8);
        // 目标文件父目录不存在 → new FileOutputStream 抛 FileNotFoundException，fos 保持 null
        File badTarget = new File("non_existent_dir_" + System.nanoTime() + File.separator + "out.txt");
        Assertions.assertThrows(java.io.FileNotFoundException.class, () -> field.transferTo(badTarget));
    }
}

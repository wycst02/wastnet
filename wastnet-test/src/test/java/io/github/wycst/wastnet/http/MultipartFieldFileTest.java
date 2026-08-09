package io.github.wycst.wastnet.http;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Unit tests for {@link MultipartFieldFile}.
 *
 * @author wangyc
 */
public class MultipartFieldFileTest {

    private static final Charset UTF_8 = StandardCharsets.UTF_8;

    @Test
    public void testFileFieldProperties() throws Exception {
        File tempFile = File.createTempFile("upload-", ".tmp");
        try {
            java.nio.file.Files.write(tempFile.toPath(), "file data".getBytes(UTF_8));
            byte[] fname = "upload.txt".getBytes(UTF_8);
            byte[] ctype = "text/plain".getBytes(UTF_8);
            HttpBuf fileName = HttpBuf.wrap(fname, 0, fname.length);
            HttpBuf contentType = HttpBuf.wrap(ctype, 0, ctype.length);

            MultipartFieldFile field = new MultipartFieldFile("upload", fileName, contentType, tempFile, UTF_8);
            Assertions.assertEquals("upload", field.getName());
            Assertions.assertEquals("upload.txt", field.getFileName());
            Assertions.assertEquals("text/plain", field.getContentType());
            Assertions.assertTrue(field.isFile());
            Assertions.assertTrue(field.isTempFile());
        } finally {
            tempFile.delete();
        }
    }

    @Test
    public void testGetDataThrowsUnsupportedOperation() throws Exception {
        File tempFile = File.createTempFile("upload-", ".tmp");
        try {
            MultipartFieldFile field = new MultipartFieldFile("upload", null, null, tempFile, UTF_8);
            Assertions.assertThrows(UnsupportedOperationException.class, () -> field.getData());
        } finally {
            tempFile.delete();
        }
    }

    @Test
    public void testGetInputStream() throws Exception {
        File tempFile = File.createTempFile("upload-", ".tmp");
        try {
            java.nio.file.Files.write(tempFile.toPath(), "stream content".getBytes(UTF_8));
            MultipartFieldFile field = new MultipartFieldFile("file", null, null, tempFile, UTF_8);
            InputStream is = field.getInputStream();
            Assertions.assertNotNull(is);
            byte[] data = new byte["stream content".length()];
            is.read(data);
            Assertions.assertEquals("stream content", new String(data, UTF_8));
        } finally {
            tempFile.delete();
        }
    }

    @Test
    public void testTransferTo() throws Exception {
        File tempFile = File.createTempFile("upload-", ".tmp");
        File destFile = File.createTempFile("dest-", ".tmp");
        try {
            java.nio.file.Files.write(tempFile.toPath(), "transfer content".getBytes(UTF_8));
            byte[] srcName = "src.txt".getBytes(UTF_8);
            HttpBuf fileName = HttpBuf.wrap(srcName, 0, srcName.length);
            MultipartFieldFile field = new MultipartFieldFile("file", fileName, null, tempFile, UTF_8);
            field.transferTo(destFile);
            Assertions.assertEquals("transfer content", new String(java.nio.file.Files.readAllBytes(destFile.toPath()), UTF_8));
        } finally {
            tempFile.delete();
            destFile.delete();
        }
    }

    @Test
    public void testTransferToWithAppend() throws Exception {
        File tempFile = File.createTempFile("upload-", ".tmp");
        File destFile = File.createTempFile("dest-", ".tmp");
        try {
            java.nio.file.Files.write(tempFile.toPath(), " appended".getBytes(UTF_8));
            java.nio.file.Files.write(destFile.toPath(), "initial".getBytes(UTF_8));
            byte[] srcName2 = "src.txt".getBytes(UTF_8);
            HttpBuf fileName = HttpBuf.wrap(srcName2, 0, srcName2.length);
            MultipartFieldFile field = new MultipartFieldFile("file", fileName, null, tempFile, UTF_8);
            field.transferTo(destFile, true);
            Assertions.assertEquals("initial appended", new String(java.nio.file.Files.readAllBytes(destFile.toPath()), UTF_8));
        } finally {
            tempFile.delete();
            destFile.delete();
        }
    }

    @Test
    public void testReleaseDeletesTempFile() throws Exception {
        File tempFile = File.createTempFile("upload-", ".tmp");
        java.nio.file.Files.write(tempFile.toPath(), "data".getBytes(UTF_8));
        MultipartFieldFile field = new MultipartFieldFile("file", null, null, tempFile, UTF_8);
        Assertions.assertTrue(tempFile.exists());
        field.release();
        Assertions.assertFalse(tempFile.exists());
    }

    @Test
    public void testGetDataAsString() throws Exception {
        File tempFile = File.createTempFile("upload-", ".tmp");
        try {
            java.nio.file.Files.write(tempFile.toPath(), "text data".getBytes(UTF_8));
            byte[] textName = "text.txt".getBytes(UTF_8);
            HttpBuf fileName = HttpBuf.wrap(textName, 0, textName.length);
            MultipartFieldFile field = new MultipartFieldFile("field", fileName, null, tempFile, UTF_8);
            // getDataAsString should throw for temp file-backed fields
            try {
                field.getDataAsString();
                Assertions.fail("Expected UnsupportedOperationException");
            } catch (UnsupportedOperationException e) {
                // expected
            }
        } finally {
            tempFile.delete();
        }
    }

    @Test
    public void testSize() throws Exception {
        File tempFile = File.createTempFile("upload-", ".tmp");
        try {
            byte[] content = "file size content".getBytes(UTF_8);
            java.nio.file.Files.write(tempFile.toPath(), content);
            MultipartFieldFile field = new MultipartFieldFile("file", null, null, tempFile, UTF_8);
            Assertions.assertEquals(content.length, field.size());
        } finally {
            tempFile.delete();
        }
    }

    // 缺口1：目标父目录不存在 → outputChannel 为 null，跳过 close
    @Test
    public void testTransferToThrowsWhenTargetUnwritable() throws Exception {
        File tempFile = File.createTempFile("upload-", ".tmp");
        try {
            java.nio.file.Files.write(tempFile.toPath(), "data".getBytes(UTF_8));
            MultipartFieldFile field = new MultipartFieldFile("file", null, null, tempFile, UTF_8);
            File badTarget = new File("non_existent_dir_" + System.nanoTime() + File.separator + "out.txt");
            Assertions.assertThrows(java.io.FileNotFoundException.class, () -> field.transferTo(badTarget));
        } finally {
            tempFile.delete();
        }
    }

    // 缺口2：源文件不存在 → inputChannel 为 null，跳过 close
    @Test
    public void testTransferToThrowsWhenSourceMissing() throws Exception {
        File missing = new File("non_existent_src_" + System.nanoTime() + ".tmp");
        MultipartFieldFile field = new MultipartFieldFile("file", null, null, missing, UTF_8);
        File dest = File.createTempFile("dest-", ".tmp");
        try {
            Assertions.assertThrows(java.io.FileNotFoundException.class, () -> field.transferTo(dest));
        } finally {
            dest.delete();
        }
    }

    // 缺口3：tempFile 为 null → release 安全跳过
    @Test
    public void testReleaseWithNullTempFile() {
        MultipartFieldFile field = new MultipartFieldFile("file", null, null, null, UTF_8);
        Assertions.assertDoesNotThrow(field::release);
    }

    // 缺口4：delete 失败 → 走 deleteOnExit 分支（非空目录 delete() 返回 false）
    @Test
    public void testReleaseFallsBackToDeleteOnExit() throws Exception {
        File dir = java.nio.file.Files.createTempDirectory("rel-").toFile();
        new File(dir, "child.txt").createNewFile(); // 非空目录，delete() 返回 false
        MultipartFieldFile field = new MultipartFieldFile("file", null, null, dir, UTF_8);
        field.release();
        Assertions.assertTrue(dir.exists()); // 未被删除，走了 deleteOnExit 分支
        // 清理
        new File(dir, "child.txt").delete();
        dir.delete();
    }
}

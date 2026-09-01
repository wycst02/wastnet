package io.github.wycst.wastnet.http;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.file.Files;

/**
 * Multipart field backed by temporary file.
 *
 * @author wangyc
 * @since 2024/3/4
 */
class MultipartFieldFile extends MultipartField {
    private final File tempFile;

    public MultipartFieldFile(String name, HttpBuf fileName, HttpBuf contentType, File tempFile, Charset charset) {
        super(name, fileName, contentType, charset);
        this.tempFile = tempFile;
    }

    @Override
    public byte[] getData() {
        throw new UnsupportedOperationException(
                "Large file uploads are backed by temporary storage. " +
                        "Use getInputStream() or transferTo() instead of getData() to avoid loading entire content into memory.");
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return Files.newInputStream(tempFile.toPath());
    }

    @Override
    public void transferTo(File file, boolean append) throws IOException {
        try (FileInputStream fis = new FileInputStream(tempFile);
             FileChannel inputChannel = fis.getChannel();
             FileOutputStream fos = new FileOutputStream(file, append);
             FileChannel outputChannel = fos.getChannel()) {
            inputChannel.transferTo(0, inputChannel.size(), outputChannel);
        }
    }

    @Override
    public boolean isTempFile() {
        return true;
    }

    /**
     * Release resources by deleting the temporary file.
     * If deletion fails, registers the file for deletion on JVM exit.
     */
    @Override
    void release() {
        if (tempFile != null && tempFile.exists()) {
            if (!tempFile.delete()) {
                tempFile.deleteOnExit();
            }
        }
    }

    @Override
    public long size() {
        return tempFile.length();
    }
}

package io.github.wycst.wastnet.http;

import java.io.*;
import java.nio.charset.Charset;

/**
 * Multipart field backed by byte array subset.
 *
 * @author wangyc
 * @since 2024/3/4
 */
class MultipartFieldData extends MultipartField {
    private final HttpBuf data;
    private byte[] __data;

    public MultipartFieldData(String name, HttpBuf fileName, HttpBuf contentType, HttpBuf data, Charset charset) {
        super(name, fileName, contentType, charset);
        this.data = data;
    }

    public MultipartFieldData(String name, HttpBuf fileName, HttpBuf contentType, byte[] data, Charset charset) {
        super(name, fileName, contentType, charset);
        this.data = HttpBuf.wrap(data, 0, data.length);
        this.__data = data;
    }

    @Override
    public byte[] getData() {
        if (__data == null) {
            __data = data.toBytes();
        }
        return __data;
    }

    @Override
    public InputStream getInputStream() {
        return new ByteArrayInputStream(data.getBuf(), data.getBegin(), data.size());
    }

    @Override
    public void transferTo(File file, boolean append) throws IOException {
        if (!isFile()) {
            throw new UnsupportedOperationException(
                    "transferTo() is only supported for file fields. " +
                            "This is a regular form field, use getData() or getInputStream() instead.");
        }
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(file, append);
            fos.write(data.getBuf(), data.getBegin(), data.size());
        } finally {
            if (fos != null) fos.close();
        }
    }

    @Override
    public long size() {
        return data.size();
    }
}

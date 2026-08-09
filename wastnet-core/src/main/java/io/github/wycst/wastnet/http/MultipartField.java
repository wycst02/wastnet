package io.github.wycst.wastnet.http;

import io.github.wycst.wastnet.util.Utils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;

/**
 * Multipart field data holder supporting both text and binary content.
 *
 * @author wangyc
 * @since 2024/3/4
 */
public abstract class MultipartField {
    private final String name;
    private final HttpBuf fileName;
    private final HttpBuf contentType;
    private final boolean file;
    private final Charset charset;
    private int index;
    private String __filename;
    private String __contentType;

    protected MultipartField(String name, HttpBuf fileName, HttpBuf contentType, Charset charset) {
        this.name = name;
        this.fileName = fileName;
        this.contentType = contentType;
        this.file = fileName != null && !fileName.isEmpty();
        this.charset = charset;
    }

    /**
     * Get the order index of this field in the multipart body (0-based).
     *
     * @return order index
     */
    public int getIndex() {
        return index;
    }

    /**
     * Set the order index (called by decoder during parsing).
     */
    void setIndex(int index) {
        this.index = index;
    }

    /**
     * Get field name
     *
     * @return field name
     */
    public String getName() {
        return name;
    }

    /**
     * Get original filename (null if regular form field)
     *
     * @return filename or null
     */
    public String getFileName() {
        if (__filename == null && fileName != null) {
            __filename = fileName.toString(charset);
        }
        return __filename;
    }

    /**
     * Get content-type of this field (null if not specified)
     *
     * @return content type or null
     */
    public final String getContentType() {
        if (__contentType == null && contentType != null) {
            __contentType = contentType.toString(charset);
        }
        return __contentType;
    }

    /**
     * Get field data as byte array.
     * Note: For large files backed by temporary storage, this method may throw
     * UnsupportedOperationException. Use getInputStream() or transferTo() instead.
     *
     * @return field data bytes
     * @throws UnsupportedOperationException if data is backed by temporary file
     */
    public abstract byte[] getData();

    /**
     * Get input stream for reading field data.
     * Preferred method for large file uploads to avoid loading entire content into memory.
     *
     * @return input stream for reading field data
     * @throws IOException if an I/O error occurs
     */
    public abstract InputStream getInputStream() throws IOException;

    /**
     * Transfer field data to a file (overwrites existing file).
     * Only supported for file upload fields (isFile() returns true).
     *
     * @param file target file
     * @throws IOException                   if an I/O error occurs
     * @throws UnsupportedOperationException if this is not a file field
     */
    public final void transferTo(File file) throws IOException {
        transferTo(file, false);
    }

    /**
     * Transfer field data to a file.
     * Only supported for file upload fields (isFile() returns true).
     *
     * @param file   target file
     * @param append if true, data is appended to existing file; if false, file is overwritten
     * @throws IOException                   if an I/O error occurs
     * @throws UnsupportedOperationException if this is not a file field
     */
    public abstract void transferTo(File file, boolean append) throws IOException;

    /**
     * Get field data as string using provided charset
     *
     * @param charset the charset to use for decoding
     * @return field data as string
     */
    public String getDataAsString(Charset charset) {
        return new String(getData(), charset);
    }

    /**
     * Get field data as string using default charset
     *
     * @return field data as string
     */
    public String getDataAsString() {
        return new String(getData(), Utils.UTF_8);
    }

    /**
     * Check if this field is a file upload
     *
     * @return true if file upload, false if regular form field
     */
    public boolean isFile() {
        return file;
    }

    /**
     * Release resources held by this field.
     * Default implementation does nothing.
     * Subclasses should override if they hold resources that need cleanup.
     */
    void release() {
        // Default: no-op
    }

    /**
     * Check if this field is backed by a temporary file.
     *
     * @return true if backed by temporary file, false otherwise
     */
    public boolean isTempFile() {
        return false;
    }

    /**
     * Get the size of this field's content in bytes.
     * @return the content size in bytes
     */
    public abstract long size() ;
}

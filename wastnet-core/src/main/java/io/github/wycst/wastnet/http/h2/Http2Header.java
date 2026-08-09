package io.github.wycst.wastnet.http.h2;

/**
 * HTTP/2 header key-value pair for HPACK static and dynamic tables.
 *
 * @author wangyc
 */
public class Http2Header {

    /**
     * Header name
     */
    public final String name;
    /**
     * Header value
     */
    public final String value;
    /**
     * Entry size per RFC 7541: name.length + value.length + 32
     */
    public final int entrySize;

    /**
     * Header name length
     */
    public final int nameLen;

    /**
     * Cached toString result
     */
    private String cache;

    /**
     * Constructor for static table.
     *
     * @param name  Header name(ascii)
     */
    Http2Header(String name) {
        this(name, "");
    }

    /**
     * Constructor for static table.
     *
     * @param name  Header name(ascii)
     * @param value Header value(ascii)
     */
    Http2Header(String name, String value) {
        this.name = name;
        this.value = value;
        this.nameLen = name.length();
        this.entrySize = nameLen + (value != null ? value.length() : 0) + 32;
    }

    Http2Header(String name, int nameLen, String value, int valueLen) {
        this.name = name;
        this.value = value;
        this.nameLen = nameLen;
        this.entrySize = nameLen + valueLen + 32;
    }

    public Http2Header self() {
        return this;
    }

    @Override
    public String toString() {
        if (cache != null) return cache;
        return cache = name + ": " + value;
    }
}

package io.github.wycst.wastnet.http.h2;

/**
 * Exception thrown during HPACK encoding/decoding errors.
 *
 * @author wangyc
 */
public class Http2HpackException extends RuntimeException {

    public Http2HpackException(String message) {
        super(message);
    }

    public Http2HpackException(String message, Throwable cause) {
        super(message, cause);
    }
}

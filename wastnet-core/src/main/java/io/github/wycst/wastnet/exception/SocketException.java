package io.github.wycst.wastnet.exception;

/**
 * @since 2024-1-14
 * @author wangyc
 */
public class SocketException extends RuntimeException {

    public SocketException(String message) {
        super(message);
    }

    public SocketException(String message, Throwable cause) {
        super(message, cause);
    }
}

package io.github.wycst.wastnet.socket.protocol;

/**
 * Pluggable object protocol adapter for {@link ObjectCodec}.
 * <p>
 * Converts between application objects and raw bytes using any binary protocol
 * (JSONB, Fury, protobuf, etc.).
 * <p>
 * A single implementation handles all message types (POJO, List, String, etc.).
 */
public interface ObjectProtocol {

    /**
     * Encode an object to bytes.
     */
    byte[] encode(Object obj) throws Exception;

    /**
     * Decode bytes to an object.
     */
    Object decode(byte[] data) throws Exception;
}

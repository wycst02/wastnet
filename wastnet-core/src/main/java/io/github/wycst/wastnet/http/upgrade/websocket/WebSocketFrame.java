package io.github.wycst.wastnet.http.upgrade.websocket;

import io.github.wycst.wastnet.http.HttpUpgradeMessage;
import io.github.wycst.wastnet.util.Utils;

import java.util.Arrays;

/**
 * WebSocket frame interface
 *
 * @since 2024-1-27
 * @author wangyc
 */
public final class WebSocketFrame implements HttpUpgradeMessage {

    final FrameType type;
    final byte[] data;
    final boolean fin;

    WebSocketFrame(FrameType type, byte[] data, boolean fin) {
        this.type = type;
        this.data = data;
        this.fin = fin;
    }

    public static WebSocketFrame textOf(String payload) {
        return new WebSocketFrame(FrameType.TEXT,
                WebSocketUtils.encodeServerFrame(FrameType.TEXT, payload.getBytes(Utils.UTF_8), true), true);
    }

    public static WebSocketFrame binaryOf(byte[] payload) {
        return new WebSocketFrame(FrameType.BINARY,
                WebSocketUtils.encodeServerFrame(FrameType.BINARY,
                        payload != null ? Arrays.copyOf(payload, payload.length) : new byte[0], true), true);
    }

    public FrameType getType() {
        return type;
    }

    public boolean isFin() {
        return fin;
    }

    public byte[] getData() {
        return data;
    }

    public WebSocketFrame merge(WebSocketFrame frame, boolean fin) {
        byte[] newData = Arrays.copyOf(data, data.length + frame.data.length);
        System.arraycopy(frame.data, 0, newData, data.length, frame.data.length);
        return new WebSocketFrame(type, newData, fin);
    }

    @Override
    public boolean isHttpRequest() {
        return false;
    }

    @Override
    public boolean isWebSocket() {
        return true;
    }

    @Override
    public boolean isUpgrade() {
        return true;
    }

    /** WebSocket frame type enum */
    public enum FrameType {
        /** Continuation frame */
        CONTINUATION(0x0),
        /** Text frame */
        TEXT(0x1),
        /** Binary frame */
        BINARY(0x2),
        /** Connection close frame */
        CLOSE(0x8),
        /** Ping frame */
        PING(0x9),
        /** Pong frame */
        PONG(0xA);

        public final int opcode;

        FrameType(int opcode) {
            this.opcode = opcode;
        }

        public static FrameType valueOf(int code) {
            switch (code) {
                case 0x0:
                    return CONTINUATION;
                case 0x1:
                    return TEXT;
                case 0x2:
                    return BINARY;
                case 0x8:
                    return CLOSE;
                case 0x9:
                    return PING;
                case 0xA:
                    return PONG;
                default:
                    throw new IllegalArgumentException("Invalid WebSocket frame type: " + code);
            }
        }
    }
}
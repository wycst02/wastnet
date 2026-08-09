package io.github.wycst.wastnet.http.upgrade.websocket;

public class WebSocketSimpleMessage {

    final WebSocketFrame.FrameType type;
    final byte[] data;
    final String text;

    WebSocketSimpleMessage(WebSocketFrame.FrameType type, byte[] data) {
        this.type = type;
        this.data = data;
        this.text = null;
    }

    WebSocketSimpleMessage(WebSocketFrame.FrameType type, String text) {
        this.type = type;
        this.text = text;
        this.data = null;
    }

    public static WebSocketSimpleMessage textOf(String text) {
        return new WebSocketSimpleMessage(WebSocketFrame.FrameType.TEXT, text);
    }

    public static WebSocketSimpleMessage binaryOf(byte[] binary) {
        return new WebSocketSimpleMessage(WebSocketFrame.FrameType.BINARY, binary);
    }
}

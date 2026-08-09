package io.github.wycst.wastnet.examples.tcp.codec;

import io.github.wycst.wastnet.socket.channel.LengthFrameCodec;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import io.github.wycst.wastnet.socket.tcp.TCPServer;

import java.io.IOException;

/**
 * 8-byte header (4 reserved + 4 length), length = total frame — server side.
 * <p>
 * Demonstrates custom header fields (flags, type) decoded in onFrame.
 */
public class TotalLengthServer {
    public static void main(String[] args) throws IOException {
        new TCPServer(9092).channelReader(new LengthFrameCodec<Void>(8, 4, 4, 65536, true) {
            protected void onFrame(ChannelContext ctx, byte[] header, byte[] body) {
                System.out.println("[server] flags=" + (header[0] & 0xFF)
                        + " type=" + (header[1] & 0xFF)
                        + " body=" + body.length + "B");
            }
        }).start();
        System.out.println("TotalLengthServer on 9092");
    }
}

package io.github.wycst.wastnet.examples.tcp.codec;

import io.github.wycst.wastnet.socket.channel.LengthFrameCodec;
import io.github.wycst.wastnet.socket.handler.ChannelHandler;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import io.github.wycst.wastnet.socket.tcp.TCPServer;

import java.io.IOException;

/**
 * Simple 4-byte big-endian length prefix + raw bytes — server side.
 */
public class SimpleByteServer {
    public static void main(String[] args) throws IOException {
        new TCPServer(9091)
                .printReadErrorLog(true)
                .channelReader(new LengthFrameCodec<byte[]>(4, 0, 4, 65536))
                .channelHandler(new ChannelHandler<byte[]>() {
                    public void onHandle(ChannelContext ctx, byte[] body) {
                        System.out.println("[server] " + body.length + "B: "
                                + new String(body, 0, Math.min(body.length, 40)));
                    }
                })
                .start();
        System.out.println("SimpleByteServer on 9091");
    }
}

package io.github.wycst.wastnet.examples.tcp.codec;

import io.github.wycst.wastnet.socket.channel.LengthFrameCodec;
import io.github.wycst.wastnet.socket.handler.ChannelHandler;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import io.github.wycst.wastnet.socket.tcp.TCPServer;

import java.io.IOException;

/**
 * Echo server: echoes back every received frame.
 */
public class EchoServer {

    public static void main(String[] args) throws IOException {
        new TCPServer(9095)
                .channelCodec(new LengthFrameCodec<byte[]>(4, 0, 4, 65536))
                .channelHandler(new ChannelHandler<byte[]>() {
                    public void onHandle(ChannelContext ctx, byte[] body) throws IOException {
                        System.out.println("[echo-server] recv " + body.length + "B");
                        ctx.send(body);
                    }
                })
                .start();
    }
}

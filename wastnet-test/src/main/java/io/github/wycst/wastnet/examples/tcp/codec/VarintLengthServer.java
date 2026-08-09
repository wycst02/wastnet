package io.github.wycst.wastnet.examples.tcp.codec;

import io.github.wycst.wastnet.socket.handler.ChannelHandler;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import io.github.wycst.wastnet.socket.tcp.TCPServer;

import java.io.IOException;

/**
 * Varint-length string protocol (replaces original ChannelStringCodec) — server side.
 */
public class VarintLengthServer {
    public static void main(String[] args) throws IOException {
        new TCPServer(9093)
                .printApplicationMessage(true)
                .channelReader(new ChannelStringCodec())
                .channelHandler(new ChannelHandler<String>() {
                    public void onHandle(ChannelContext ctx, String message) throws IOException {
                        System.out.println("[server] got \"" + message + "\"");
                        ctx.send(message);
                    }
                })
                .start();
        System.out.println("VarintLengthServer on 9093");
    }
}

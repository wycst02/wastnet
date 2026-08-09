package io.github.wycst.wastnet.examples.tcp.codec;

import io.github.wycst.wastnet.socket.handler.ChannelHandler;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import io.github.wycst.wastnet.socket.tcp.TCPClient;

import java.io.IOException;

/**
 * Varint-length string protocol — client side (uses ChannelStringCodec for writing).
 */
public class VarintLengthClient {
    public static void main(String[] args) throws IOException, InterruptedException {
        TCPClient<String> client = new TCPClient<String>("localhost", 9093)
                .channelCodec(new ChannelStringCodec())
                .channelHandler(new ChannelHandler<String>() {
                    public void onHandle(ChannelContext ctx, String message) {
                        System.out.println("[client] got \"" + message + "\"");
                    }
                })
                .connect();
        String[] msgs = {"hello", "world", "varint works!"};
        for (String msg : msgs) {
            client.send(msg);  // String 自动通过 codec 编码
            System.out.println("[client] sent \"" + msg + "\"");
            Thread.sleep(300);
        }
        client.close();
    }
}

package io.github.wycst.wastnet.examples.tcp.codec;

import io.github.wycst.wastnet.socket.channel.LengthFrameCodec;
import io.github.wycst.wastnet.socket.handler.ChannelHandler;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import io.github.wycst.wastnet.socket.tcp.TCPClient;

import java.io.IOException;

/**
 * Echo client: sends messages and prints server echo responses.
 */
public class EchoClient {

    public static void main(String[] args) throws IOException, InterruptedException {
        TCPClient<String> client = new TCPClient<String>("localhost", 9095)
                .channelCodec(new LengthFrameCodec<String>(4, 0, 4, 65536) {
                    protected byte[] encodeBody(ChannelContext ctx, String message) {
                        return message.getBytes();
                    }
                })
                .printReadErrorLog(true)
                .channelHandler(new ChannelHandler<byte[]>() {
                    public void onHandle(ChannelContext ctx, byte[] body) throws IOException {
                        System.out.println("[echo-client] echo " + body.length + "B: "
                                + new String(body, 0, Math.min(body.length, 40)));
                        // ctx.writeFlush(codec.encode("echo-client-response"));
                    }
                })
                .connect();

        String[] msgs = {"hello", "echo!", "bidirectional"};
        for (String msg : msgs) {
            client.send(msg);
            Thread.sleep(500);
        }
        Thread.sleep(100000);
        client.close();
    }
}

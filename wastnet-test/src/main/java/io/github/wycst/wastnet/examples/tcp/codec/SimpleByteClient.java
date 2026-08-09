package io.github.wycst.wastnet.examples.tcp.codec;

import io.github.wycst.wastnet.socket.channel.LengthFrameCodec;
import io.github.wycst.wastnet.socket.tcp.TCPClient;

import java.io.IOException;

/**
 * Simple 4-byte big-endian length prefix + raw bytes — client side.
 */
public class SimpleByteClient {
    public static void main(String[] args) throws IOException, InterruptedException {
        TCPClient<byte[]> client = new TCPClient<byte[]>("localhost", 9091)
                .channelCodec(new LengthFrameCodec<byte[]>(4, 0, 4, 65536))
                .connect();
        byte[] msg = "Hello LengthFieldCodec!".getBytes();
        for (int i = 0; i < 5; ++i) {
            client.send(msg);  // 自动通过 codec 编码后发送
            System.out.println("[client] sent " + msg.length + "B");
            Thread.sleep(500);
        }
        client.close();
    }
}

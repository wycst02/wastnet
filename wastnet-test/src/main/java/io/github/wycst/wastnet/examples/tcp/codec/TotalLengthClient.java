package io.github.wycst.wastnet.examples.tcp.codec;

import io.github.wycst.wastnet.socket.channel.LengthFrameCodec;
import io.github.wycst.wastnet.socket.tcp.TCPClient;

import java.io.IOException;

/**
 * 8-byte header (4 reserved + 4 length), length = total frame — client side.
 * <p>
 * Overrides createHeader to populate flags/type fields.
 */
public class TotalLengthClient {
    public static void main(String[] args) throws IOException, InterruptedException {
        TCPClient<byte[]> client = new TCPClient<byte[]>("localhost", 9092)
                .channelCodec(new LengthFrameCodec<byte[]>(8, 4, 4, 65536, true) {
                    protected byte[] createHeader(int wireLength) {
                        byte[] h = new byte[8];
                        h[0] = 1;   // flags
                        h[1] = 2;   // type
                        writeLength(h, 4, wireLength);
                        return h;
                    }
                })
                .connect();
        client.send("TotalLength demo".getBytes());
        Thread.sleep(500);
        client.close();
    }
}

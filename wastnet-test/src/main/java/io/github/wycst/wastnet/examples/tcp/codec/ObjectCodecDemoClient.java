package io.github.wycst.wastnet.examples.tcp.codec;

import io.github.wycst.wastnet.socket.handler.ChannelHandler;
import io.github.wycst.wastnet.socket.protocol.ObjectCodec;
import io.github.wycst.wastnet.socket.protocol.ObjectProtocol;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import io.github.wycst.wastnet.socket.tcp.TCPClient;

import java.io.IOException;

/**
 * ObjectCodec demo — client side.
 * <p>
 * Sends POJO messages to the server and prints server echo replies.
 */
public class ObjectCodecDemoClient {

    // ==================== POJO ====================

    public static class Message {
        String content;
        long timestamp;

        public Message() {
        }

        public Message(String content) {
            this.content = content;
            this.timestamp = System.currentTimeMillis();
        }

        @Override
        public String toString() {
            return "Message{content='" + content + "', timestamp=" + timestamp + '}';
        }
    }

    // ==================== Main ====================

    public static void main(String[] args) throws Exception {
        ObjectProtocol protocol = new ObjectProtocol() {
            public byte[] encode(Object obj) throws IOException {
                String json = "{\"content\":\"" + ((Message) obj).content + "\"}";
                return json.getBytes("UTF-8");
            }

            public Object decode(byte[] data) throws IOException {
                String json = new String(data, "UTF-8");
                String content = json.substring(json.indexOf("\"content\":\"") + 11, json.lastIndexOf("\"}"));
                return new Message(content);
            }
        };

        TCPClient<Message> client = new TCPClient<Message>("localhost", 9096);
        client.channelCodec(new ObjectCodec<Message>(65536, protocol, 0x57534E54, false));
        client.channelHandler(new ChannelHandler<Message>() {
            public void onHandle(ChannelContext ctx, Message msg) throws IOException {
                System.out.println("[client] received " + msg);
            }
        });
        client.connect();

        for (int i = 0; i < 10; ++i) {
            client.send(new Message("hello-" + i));
        }

        Thread.sleep(2000);
        client.close();
    }
}

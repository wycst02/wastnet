package io.github.wycst.wastnet.examples.tcp.ssl;

import io.github.wycst.wastnet.socket.channel.ChannelCodec;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import io.github.wycst.wastnet.socket.tcp.NioConfig;
import io.github.wycst.wastnet.socket.tcp.TCPClient;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * TCPClient 同步 TLS 握手 + 加密 HTTP 往返验证：连接真实 HTTPS 站点发送 GET，接收解密后的响应。
 */
public class SslClientTest {

    public static void main(String[] args) throws Exception {
        String[] hosts = args.length > 0 ? args
                : new String[]{"www.baidu.com", "www.sina.com.cn", "github.com"};
        for (String host : hosts) {
            test(host);
        }
    }

    static void test(String host) throws Exception {
        CountDownLatch got = new CountDownLatch(1);
        StringBuilder resp = new StringBuilder();
        TCPClient<byte[]> client = new TCPClient<byte[]>(host, 443, new NioConfig());
        client.ssl(true).connectTimeout(8000).channelCodec(new ChannelCodec<byte[]>() {
            public void decode(ChannelContext ctx, ByteBuffer buf) {
                byte[] b = new byte[Math.min(buf.remaining(), 200)];
                buf.get(b);
                resp.append(new String(b));
                got.countDown();
            }
            public ByteBuffer write(ChannelContext ctx, byte[] msg) {
                return ByteBuffer.wrap(msg);
            }
        });
        long t0 = System.currentTimeMillis();
        try {
            client.connect();
            long cost = System.currentTimeMillis() - t0;
            client.send(("GET / HTTP/1.0\r\nHost: " + host + "\r\nUser-Agent: test\r\nConnection: close\r\n\r\n").getBytes());
            boolean ok = got.await(8, TimeUnit.SECONDS);
            String head = resp.toString().replaceAll("\\s+", " ");
            if (head.length() > 60) head = head.substring(0, 60);
            System.out.println(host + ": connect+TLS=" + cost + "ms, response=" + ok + " firstLine=[" + head + "]");
        } catch (Exception e) {
            System.out.println(host + ": FAIL " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            client.close();
        }
    }
}

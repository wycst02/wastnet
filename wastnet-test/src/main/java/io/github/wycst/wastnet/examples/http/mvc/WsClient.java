package io.github.wycst.wastnet.examples.http.mvc;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Base64;

/**
 * 简易 WebSocket 客户端 — 测试 /ws/chat 端点.
 * <p>
 * 运行 {@link MvcDemo} 后再运行此类。
 *
 * @author wangyc
 */
public class WsClient {

    public static void main(String[] args) throws Exception {
        String host = "localhost";
        int port = 8080;
        String path = "/ws/chat";

        // 1. 建立 TCP 连接
        Socket socket = new Socket(host, port);
        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();

        // 2. WebSocket 握手
        String key = Base64.getEncoder().encodeToString("dGhlIHNhbXBsZSBub25jZQ==".getBytes());
        String httpUpgrade = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + host + ":" + port + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "\r\n";
        out.write(httpUpgrade.getBytes());
        out.flush();

        // 3. 读取握手响应
        byte[] buf = new byte[4096];
        int len = in.read(buf);
        String response = new String(buf, 0, len);
        if (!response.contains("101 Switching Protocols")) {
            System.out.println("Handshake failed:\n" + response);
            socket.close();
            return;
        }
        System.out.println("[ws] connected to ws://" + host + ":" + port + path);

        // 4. 发送文本帧（掩码）
        String text = "Hello WebSocket!";
        byte[] payload = text.getBytes("UTF-8");
        byte[] frame = new byte[6 + payload.length];
        frame[0] = (byte) 0x81; // FIN + text opcode
        frame[1] = (byte) (0x80 | payload.length); // masked + length
        byte[] mask = {0x01, 0x02, 0x03, 0x04};
        System.arraycopy(mask, 0, frame, 2, 4);
        for (int i = 0; i < payload.length; ++i) {
            frame[6 + i] = (byte) (payload[i] ^ mask[i % 4]);
        }
        out.write(frame);
        out.flush();
        System.out.println("[ws] sent: " + text);

        // 5. 读取回显
        len = in.read(buf);
        if (len > 1) {
            byte[] responsePayload = new byte[len - 2];
            System.arraycopy(buf, 2, responsePayload, 0, len - 2);
            System.out.println("[ws] received: " + new String(responsePayload, "UTF-8"));
        }

        // 6. 发送 close 帧
        byte[] closeFrame = new byte[2];
        closeFrame[0] = (byte) 0x88; // FIN + close opcode
        closeFrame[1] = 0x00;
        out.write(closeFrame);
        out.flush();
        Thread.sleep(100); // 等服务器处理

        socket.close();
        System.out.println("[ws] done");
    }
}

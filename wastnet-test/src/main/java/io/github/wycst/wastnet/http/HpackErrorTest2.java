package io.github.wycst.wastnet.http;

import io.github.wycst.wast.common.utils.ByteUtils;
import io.github.wycst.wastnet.http.h2.Http2HpackCodec;
import io.github.wycst.wastnet.http.h2.HuffmanByteCodec;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class HpackErrorTest2 {

    public static void main(String[] args) {

        // 00010
        // 11111111|010
        byte[] hpack0 = HuffmanByteCodec.encodeData(new byte[] {'}', '3', '\\', (byte) 255});
        hpack0 = HuffmanByteCodec.encodeData(new byte[] {'|', '|', '|'}); // }4]
        hpack0 = HuffmanByteCodec.encodeData(new byte[] {'|', '2', -1}); // }4]
        hpack0 = HuffmanByteCodec.encodeData(new byte[] {'"', '0'}); // }4]
        hpack0 = HuffmanByteCodec.encodeData(new byte[] {(byte) 0xff, (byte) 0xeb, 0x74}); // }4]
//        hpack0 = ByteUtils.hexString2Bytes("A0 E4 1D 13 9D 09 B8 F0 1E 07");
        byte[] result = new byte[1024];
        System.out.println((byte)240);

        // 1101111 11111111 11011111
        for (int i = 0; i < hpack0.length; i++) {
            System.out.println(Integer.toBinaryString(hpack0[i] & 0xFF));
        }
        // 发现加密和报文里面的cookie编码一致说明HuffmanByteCodec编码逻辑没有问题

        int count = 0;
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 1; i++) {
            count = HuffmanByteCodec.decodeData(hpack0, 0, hpack0.length, result, 0);
        }
        long end = System.currentTimeMillis();
        System.out.println("耗时：" + (end - begin) + "ms");
        System.out.println("result = " + new String(result, 0, count, StandardCharsets.UTF_8));

        // Map<String, Object> map = http2HpackCodec.decode(hpackDatas);
        // System.out.println(map);
    }


}

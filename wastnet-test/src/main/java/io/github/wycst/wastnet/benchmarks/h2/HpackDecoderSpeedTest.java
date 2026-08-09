package io.github.wycst.wastnet.benchmarks.h2;

import io.github.wycst.wast.common.utils.ByteUtils;
import io.github.wycst.wastnet.http.h2.Http2HpackCodec;
import io.github.wycst.wastnet.util.Utils;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * @since 2024-2-28
 * @author wangyc
 */
public class HpackDecoderSpeedTest {


    public static void main(String[] args) {
        byte[] b2 = new byte[]{-7, -7, -7};
        System.out.println(new String(b2, StandardCharsets.ISO_8859_1));

        byte[] bytes = ByteUtils.hexString2Bytes("00 85 25 A8 49 E9 0F FF C8 03 FF C7 FF FD 8F FF FF E2 FF FF FE 3F FF FF E4 FF FF FE 5F FF FF E6 FF FF FE 7F FF FF E8 FF FF EA FF FF FF F3 FF FF FA 7F FF FF AB FF FF FF DF FF FF EB FF FF FE CF FF FF ED FF FF FE EF FF FF EF FF FF FF 0F FF FF F1 FF FF FF 2F FF FF FF BF FF FF CF FF FF FD 3F FF FF D7 FF FF FD BF FF FF DF FF FF FE 3F FF FF E7 FF FF FE BF FF FF ED 4F E3 F9 FF AF FC AB F1 FE BF AF EF E7 FD FD 2C BB 00 08 99 69 B7 1D 79 FB 9F 7F FF 20 FF BF F3 FF 50 DD BD 7F 06 1C 58 F2 65 CD 9F 46 9D 5A F6 6D DD BF 87 1E 5F 9C FF 7F F7 FF FC 3F F9 FF E4 5F FF 47 19 24 2C B3 4E 6E 9D 68 A6 A3 D7 DA C4 26 DE FE 3C FA F7 FF FB FE 7F FB FF DF FF FF FC FF FE 6F FF F4 BF FF 9F FF FA 3F FF D3 FF FF 53 FF FD 5F FF FB 3F FF EB 7F FF DA FF FF B7 FF FF 73 FF FE EF FF FD EF FF FE BF FF FB FF FF FD 9F FF FD BF FF EB FF FF E0 FF FF EE FF FF C3 FF FF 8B FF FF 1F FF FE 4F FF EE 7F FF B1 FF FF 97 FF FD 9F FF FC DF FF F9 FF FF FB FF FF DA FF FE EF FF F4 FF FF B7 FF FE E7 FF FE 8F FF FD 3F FF DE FF FF D5 FF FE EF FF FB DF FF FE 1F FF DF FF FF 7F FF FF 5F FF FE CF FF F0 7F FF 87 FF FE 0F FF F1 7F FF ED FF FF 87 FF FF 77 FF FE FF FF EA FF FF 8B FF FE 3F FF F9 3F FF F8 7F FF CB FF FF 37 FF FF 1F FF FF 83 FF FF E1 FF FE BF FF E3 FF FF 3F FF FF 2F FF FA 3F FF FD 9F FF FF 17 FF FF C7 FF FF F2 7F FF FD EF FF FF BF FF FF F2 FF FF F8 FF FF FB 7F FF 97 FF F8 FF FF FE 6F FF FF C1 FF FF F8 7F FF FE 7F FF FF C5 FF FF E5 FF FE 4F FF F2 FF FF FD 1F FF FF 4F FF FF FE FF FF FE 3F FF FF C9 FF FF F9 7F FF B3 FF FF CF FF FB 7F FF CD FF FF 4F FF F9 FF FF D1 FF FF CF FF FE AF FF FA FF FF FD DF FF FE FF FF FF 4F FF FF 5F FF FF AB FF FF A7 FF FF D7 FF FF F9 BF FF FE CF FF FF B7 FF FF F3 FF FF FE 8F FF FF D3 FF FF FA BF FF FF 5F FF FF FF 7F FF FE CF FF FF DB FF FF FB BF FF FF 7F FF FF F0 FF FF FB BF");
//        bytes = ByteUtils.hexString2Bytes("82 41 8A A0 E4 1D 13 9D 09 B8 F3 EF BF 87 04 87 63 AD 28 61 25 42 7F 58 87 A4 7E 56 1C C5 80 1F");
//
//        bytes = ByteUtils.hexString2Bytes("40 87 41 48 B1 27 5A D1 FF B8 FE 74 9D 2A 43 FD 5D B0 75 49 FC FD F7 83 F9 7D FF E7 E9 4F E7 11 CF 35 05 52 F4 F6 1E 92 FF 3F 7D E0 FE 42 20 FF 3F 4A 7F 37 A7 B0 F4 9A DA 7F 9F BE F0 7F 21 10 7F 9F");
//        bytes = ByteUtils.hexString2Bytes("82 41 8A A0 E4 1D 13 9D 09 B8 F3 EF BF 87 04 87 63 AD 28 61 25 42 7F 58 87 A4 7E 56 1C C5 80 1F 40 87 41 48 B1 27 5A D1 FF B8 FE 74 9D 2A 43 FD 5D B0 75 49 FC FD F7 83 F9 7D FF E7 E9 4F E7 11 CF 35 05 52 F4 F6 1E 92 FF 3F 7D E0 FE 42 20 FF 3F 4A 7F 37 A7 B0 F4 9A DA 7F 9F BE F0 7F 21 10 7F 9F");
//        bytes = ByteUtils.hexString2Bytes("41 85 FF FF FF E4 F4");

//        bytes = ByteUtils.hexString2Bytes("60ff9f01b648e28cded27ea5aa0876435f0ee2ed32db6fe8e9e62f57681797f37edfe7dfdeae61e7af2f2d47eb9d3b6693577a6f39448ffef7c5b2f35401fcd6e9d5fbeefdb94f87321ffdf4e603dba9c7b38f369efb105c99cadcdaa366ce8ce3b3f7c8f3d768013e1bb1bc5ed80185d75c4c9adbc266f0f5f4cead7ca8f3cfad43eb95cf3e7c4bcbd7805d063acddaa3f18e9b218abc79fab94f86b568e04ef8f287093de0f3abf330798e7d116bbf37e1bc7bf08e1e16d97d1bebbfca6c168dd260987e13d192e7e19fbdfd3811c2ff2d5e51c747ef2e7f92facdcfb370f5ef26a781b2d37bc7a538653f2679136446e4c930e367767ebef4688747ac753e9aacfde64e9beb681bf5245ba25bafe32fe7e9a0f379ecd6bf27a8fc3df6f65f16b3");
//        bytes = ByteUtils.hexString2Bytes("887685de5aa635455f87352398ac5754df0f0d840b4e85af6196dc34fd28102984b1a82009a500edc69bb8d3ca62d1bf6496d07abe940854d03b141004d28076e34ddc69e53168df588ba47e561cc5804dbe20001f52848fd24a8f40921d06591e0d2a569a83c63a1640fb9526a4bf870ba075b6c2169e7cd120c9395a717a044a99089bffd83e81f4c81a581f58fff9f4a10649cae2f40895321137ffb0fd03ff9f4a7720c9395b5c9536f01affd83e81f42001607d63ffe7d29dc8324e5717254dbc06bff62fa07ff36c96df697e94036a5f29141002ea807ae321b81754c5a37f0f138cfe5b189c6df6db5997dd1ff30f06850bcebad05f4085f2b10649cb9ac7937a9bef6b8b460d1163c9bd490d6557022b81034f89b13ccf408cf2b23c1a54ac81f72a4d497f96df697e940b2a612c6a080269408ae360b8d854c5a37f408cf2b23c1a54ac419272a4d49785132169a7dd408e49a935532c3a283f858f61a6355f012a408528e6a0a6939375f085d8df185d03ed09e6dc75f79d79f004bf");


//        String bs = ByteUtils.hexToBinaryString("41 8A A0 E4 1D 13 9D 09 B8 F3 EF BF");
        // 10100000 11100100 00011101 00010011 10011101 00001001 10111000 11110 011111(9) 011111(9) 0111111(9)
        // localhost:8999
        // :method: GET, :authority: localhost:8999, :scheme: https, :path: /kms/test, cache-control: max-age=0, sec-ch-ua: "Not A(Brand";v="99", "Google Chrome";v="121", "Chromium";v="121", sec-ch-ua-mobile: ?0, sec-ch-ua-platform: "Windows", upgrade-insecure-requests: 1, user-agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36, accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7, sec-fetch-site: none, sec-fetch-mode: navigate, sec-fetch-user: ?1, sec-fetch-dest: document, accept-encoding: gzip, deflate, br, accept-language: zh-CN,zh;q=0.9
        Http2HpackCodec http2HpackDecoder = new Http2HpackCodec(Utils.UTF_8);

        http2HpackDecoder = new Http2HpackCodec();

        long l1 = System.currentTimeMillis();
        for (int i = 0; i < 1000000; ++i) {
            http2HpackDecoder.decode(bytes);
        }
        long l2 = System.currentTimeMillis();
        System.out.println("use " + (l2 - l1));

        http2HpackDecoder.decode(bytes);
//        System.out.println(http2HpackDecoder.decode(new byte[] {(byte) 0xCF}));
//        System.out.println(http2HpackDecoder.decode(new byte[] {(byte) 0xBF}));

        Map<String, Object> headers = http2HpackDecoder.decode(bytes);
        System.out.println(headers);
        System.out.println(headers.size());
    }
}

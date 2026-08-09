package io.github.wycst.wastnet.examples.h2;

import io.github.wycst.wast.common.utils.ByteUtils;
import io.github.wycst.wastnet.http.h2.Http2HpackCodec;
import io.github.wycst.wastnet.util.Utils;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * @since 2024-2-28
 * @author wangyc
 */
public class HpackDecoderTest {


    public static void main(String[] args) {
        byte[] b2 = new byte[]{-7, -7, -7};
        System.out.println(new String(b2, StandardCharsets.ISO_8859_1));

        byte[] bytes = ByteUtils.hexString2Bytes("82 41 8A A0 E4 1D 13 9D 09 B8 F3 EF BF 87 04 " +
                "87 63 AD 28 61 25 42 7F 58 87 A4 7E 56 1C C5 80 1F 40 87 41 " +
                "48 B1 27 5A D1 FF B8 FE 74 9D 2A 43 FD 5D B0 75 49 FC FD F7 " +
                "83 F9 7D FF E7 E9 4F E7 11 CF 35 05 52 F4 F6 1E 92 FF 3F 7D " +
                "E0 FE 42 20 FF 3F 4A 7F 37 A7 B0 F4 9A DA 7F 9F BE F0 7F 21 " +
                "10 7F 9F 40 8B 41 48 B1 27 5A D1 AD 49 E3 35 05 02 3F 30 40 " +
                "8D 41 48 B1 27 5A D1 AD 5D 03 4C A7 B2 9F 88 FE 79 1A A9 0F " +
                "E1 1F CF 40 92 B6 B9 AC 1C 85 58 D5 20 A4 B6 C2 AD 61 7B 5A " +
                "54 25 1F 01 31 7A D5 D0 7F 66 A2 81 B0 DA E0 53 FA E4 6A A4 " +
                "3F 84 29 A7 7A 81 02 E0 FB 53 91 AA 71 AF B5 3C B8 D7 F6 A4 " +
                "35 D7 41 79 16 3C C6 4B 0D B2 EA EC B8 A7 F5 9B 1E FD 19 FE " +
                "94 A0 DD 4A A6 22 93 A9 FF B5 2F 4F 61 E9 2B 01 10 57 02 E0 " +
                "5C 0A 6E 1C A3 B0 CC 36 CB AB B2 E7 53 E5 49 7C A5 89 D3 4D " +
                "1F 43 AE BA 0C 41 A4 C7 A9 8F 33 A6 9A 3F DF 9A 68 FA 1D 75 " +
                "D0 62 0D 26 3D 4C 79 A6 8F BE D0 01 77 FE 8D 48 E6 2B 03 EE " +
                "69 7E 8D 48 E6 2B 1E 0B 1D 7F 46 A4 73 15 81 D7 54 DF 5F 2C " +
                "7C FD F6 80 0B BD F4 3A EB A0 C4 1A 4C 7A 98 41 A6 A8 B2 2C " +
                "5F 24 9C 75 4C 5F BE F0 46 CF DF 68 00 BB BF 40 8A 41 48 B4 " +
                "A5 49 27 59 06 49 7F 83 A8 F5 17 40 8A 41 48 B4 A5 49 27 5A " +
                "93 C8 5F 86 A8 7D CD 30 D2 5F 40 8A 41 48 B4 A5 49 27 5A D4 " +
                "16 CF 02 3F 31 40 8A 41 48 B4 A5 49 27 5A 42 A1 3F 86 90 E4 " +
                "B6 92 D4 9F 50 8D 9B D9 AB FA 52 42 CB 40 D2 5F A5 23 B3 51 " +
                "8C F7 3A D7 B4 FD 7B 9F EF B4 00 5D FF");
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
        Http2HpackCodec http2HpackDecoder = new Http2HpackCodec(Utils.ISO_8859_1);

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

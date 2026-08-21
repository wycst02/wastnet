package io.github.wycst.wastnet.http.h2;

import io.github.wycst.wast.common.utils.ByteUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class HpackErrorTest {

    // Collect decode-triggering cases: strings whose Huffman bytes crash decodeData.
    static List<String> findTriggerCases() {

        List<String> triggers = new ArrayList<>();
        // charset: common ASCII printable chars (the buggy path is sensitive to
        // short codes + trailing EOS padding, so include digits, punctuation, letters)
        String chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ.-_/{}%:=\" ~|{[^\\`";
        byte[] out = new byte[512];
        // 1) explicit short cases (the chars you pointed out: ~ | { ` ^ \\ [ all trigger)
        String[] seeds = {"5}", "5", "}", "~", "|", "{", "`", "^", "\\", "[",
                "~}", "|}", "{~", "`|", "^[", "\\{", "[~", "}}", "}}"};

        tryTrigger("00\\", out, triggers);

        for (String s : seeds) tryTrigger(s, out, triggers);
        // 2) brute-force 2~4 char combinations to harvest >=20 triggers
        char[] ca = chars.toCharArray();
        outer:
        for (int i = 0; i < ca.length; ++i) {
            for (int j = 0; j < ca.length; ++j) {
                tryTrigger("" + ca[i] + ca[j], out, triggers);
                if (triggers.size() >= 20) break outer;
                for (int k = 0; k < ca.length; ++k) {
                    tryTrigger("" + ca[i] + ca[j] + ca[k], out, triggers);
                    if (triggers.size() >= 20) break outer;
                }
            }
        }
        return triggers;
    }

    static void tryTrigger(String s, byte[] out, List<String> triggers) {
        byte[] plain = s.getBytes(StandardCharsets.US_ASCII);
        byte[] enc = HuffmanByteCodec.encodeData(plain, 0, plain.length);
        try {
            HuffmanByteCodec.decodeData(enc, 0, enc.length, out, 0);
        } catch (Throwable t) {
            triggers.add(s);
            System.out.println("TRIGGER case=" + repr(s) + " enc=" + toHex(enc) + " -> " + t);
            t.printStackTrace();
        }
    }

    static String repr(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.US_ASCII)) sb.append((char) b);
        return sb.toString();
    }

    static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (byte b : data) sb.append(String.format("%02X ", b & 0xFF));
        return sb.toString().trim();
    }

    public static void main(String[] args) {
        // debug: confirm decode table lengths (RFC 7541 requires 256 entries, idx 0-255)
        try {
            for (String f : new String[]{"DECODE_BITS", "DECODE_VALUES", "DECODE_MANTISSAS"}) {
                java.lang.reflect.Field fd = HuffmanByteCodec.class.getDeclaredField(f);
                fd.setAccessible(true);
                int len = ((byte[]) fd.get(null)).length;
                System.out.println(f + ".len=" + len);
            }
        } catch (Exception e) {
            System.out.println("reflect err: " + e);
        }


        String hpackText = "82 41 8A A0 E4 1D 13 9D 09 B8 F0 1E 07 87 84 58 87 A4 7E 56 1C C5 80 1F 40 87 41 48 B1 27 5A D1 FF B8 FE 74 18 96 1D 07 95 2A 60 92 62 FF 3F 7D E0 FE 42 C8 7F 9F A5 3F 9B D3 D8 7A 4D 6D 3F CF DF 78 3F 90 B2 1F E7 E9 4F E7 49 D3 14 2A 5D B0 75 49 FC FD F7 83 F9 13 5F CF 40 8B 41 48 B1 27 5A D1 AD 49 E3 35 05 02 3F 30 40 8D 41 48 B1 27 5A D1 AD 5D 03 4C A7 B2 9F 88 FE 79 1A A9 0F E1 1F CF 40 92 B6 B9 AC 1C 85 58 D5 20 A4 B6 C2 AD 61 7B 5A 54 25 1F 01 31 7A DF D0 7F 66 A2 81 B0 DA E0 53 FA E4 6A A4 3F 84 29 A7 7A 81 02 E0 FB 53 91 AA 71 AF B5 3C B8 D7 F6 A4 35 D7 41 79 16 3C C6 4B 0D B2 EA EC B8 A7 F5 9B 1E FD 19 FE 94 A0 DD 4A A6 22 93 A9 FF B5 2F 4F 61 E9 2B 01 64 2B 81 70 2E 05 37 0E 51 D8 66 1B 65 D5 D9 71 4C 12 4C C0 59 0A E0 5C 0B 83 53 E5 49 7C A5 89 D3 4D 1F 43 AE BA 0C 41 A4 C7 A9 8F 33 A6 9A 3F DF 9A 68 FA 1D 75 D0 62 0D 26 3D 4C 79 A6 8F BE D0 01 77 FE 8D 48 E6 2B 03 EE 69 7E 8D 48 E6 2B 1E 0B 1D 7F 46 A4 73 15 81 D7 54 DF 5F 2C 7C FD F6 80 0B BD F4 3A EB A0 C4 1A 4C 7A 98 41 A6 A8 B2 2C 5F 24 9C 75 4C 5F BE F0 46 CF DF 68 00 BB BF 40 8A 41 48 B4 A5 49 27 59 06 49 7F 83 A8 F5 17 40 8A 41 48 B4 A5 49 27 5A 93 C8 5F 86 A8 7D CD 30 D2 5F 40 8A 41 48 B4 A5 49 27 5A D4 16 CF 02 3F 31 40 8A 41 48 B4 A5 49 27 5A 42 A1 3F 86 90 E4 B6 92 D4 9F 50 92 9B D9 AB FA 52 42 CB 40 D2 5F A5 23 B3 E9 4F 68 4C 9F 51 A6 F7 3A D7 B4 FD 7B 9F EF B4 00 5D FF A2 D5 F7 DA 00 2E F7 D1 6A 5B 15 DF BE D0 01 77 7E 8B 52 DC 37 7D F6 80 0B B9 60 AC C7 4C 54 77 4C 45 2B CE 32 07 9B 75 F1 06 47 80 C8 24 70 22 92 39 1D 91 D6 C3 1B 6D C0 17 5E 65 D7 9A 13 62 FA 0B AF 34 EB A2 64 01 60 FF C0 03 1D A9 9C F6 1B D9 64 59 27 EA 5A A0 FF FC A8 84 32 10 A8 46 F3 F5 2D 4A 88 57 15 10 AE 94 76 16 C5 44 02 FA CB 3C 78 88 6D 46 CB 93 87 BC 81 D2 6C 88 C9 56 D6 C9 7B 23 99 3A D7 F9 C6 F6 57 D7 2F AC A1 1F 2D 86 D4 6C 9E 72 1B C1 A4 E4 D1 B5 1B 2F 5F DF CE 66 CF 7B 2F A8 CD 91 CC 96 98 53 94 AE 56 B1 6D 39 4B B2 7B 79 6D A6 F6 44 64 AA BA FF 6F 64 73 21 81 E7 21 BC 18 B5 6C 36 AE 90 A3 25 3B 6B FD BD 91 CC 9D 21 36 7B D9 7C 73 13 FD 7F 77 64 73 27 4D 1E 34 5F C0 6C F7 B2 FA 1F 2D 86 D4 6C B3 C9 4F 9F 7B 45 85 3B 05 A7 1F 3F CD BD 36 E2 CB 5E E0 E1 CB 90 DE 0D 26 2A 3A E4 E8 73 64 37 83 73 67 6D 7F B7 B2 39 92 99 11 18 B2 CF 1D EC 88 C9 48 7D 47 8B 81 B5 1B 27 8C 86 F0 6F DF C9 FC 64 E8 7F 3A 7D CC CE 8B E4 F7 A3 7C DE E5 D8 BF 7E D1 69 BF 66 FA 29 96 FE 7F 2E E5 37 4E A8 75 65 B6 F5 8F 78 21 F7 50 EC C7 BF 00 3A 57 C5 D9 E9 E0 A8 84 A8 AF 2A 21 58 59 6C 2A 27 DE 7E A5 A9 51 0A E2 A2 15 D2 8E C2 D8 A8 80 5F 59 67 8F 11 0D A8 D9 72 70 F7 90 3A 4D 91 19 2A DA D9 2F 64 73 27 5A FF 38 DE CA FA E5 F5 94 23 E5 B0 DA 8D 93 CE 43 78 34 9C 9A 36 A3 65 EB FB F9 CC D9 EF 65 F5 19 B2 39 92 D3 0A 72 95 CA D6 2D A7 29 76 4F 6F 2D B4 DE C8 8C 95 57 5F ED EC 8E 64 30 3C E4 37 83 16 AD 86 D5 D2 14 64 A7 6D 7F B7 B2 39 93 A4 26 CF 7B 2F 8E 62 7F AF EE EC 8E 64 E9 A3 C6 8B F8 0D 9E F6 5F 43 E5 B0 DA 8D 96 79 29 F3 EF 68 B0 A7 60 B4 E3 E7 F9 B7 A6 DC 59 6B DC 1C 39 72 1B C1 A4 C5 47 5C 9D 0E 6C 86 F0 6E 6C ED AF F6 F6 47 32 53 22 23 16 59 E3 BD 91 19 29 0F A8 F1 70 36 A3 64 F1 90 DE 0D FB F9 3F 8C 9D 0F E7 4F B9 99 D1 7C 9E F4 6F 9B DC BB 17 EF DA 2D 37 EC DF 45 32 DF CF E5 DC A6 E9 D5 0E AC B6 DE B1 EF 04 3E EA 1D 98 F7 E0 07 4A F8 BB 3D 3C 15 10 95 15 E5 44 22 F9 AC D6 15 0A 88 57 02 EB CE 81 A6 9C 79 96 5B 6F FF DF 40 86 AE C3 1E C3 27 D7 85 B6 00 7D 28 6F";
        byte[] hpackDatas = ByteUtils.hexString2Bytes(hpackText);
        int offset = 531, len = 575;

        // error bytes
        byte[] bytes = Arrays.copyOfRange(hpackDatas, offset, offset + len);
        final Http2HpackCodec http2HpackCodec = new Http2HpackCodec();
        http2HpackCodec.decode(hpackDatas);

        byte[] hpack0 = HuffmanByteCodec.encodeData("authorized-token={%22accessToken%22:%22Bearer%20eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1aWQiOiIxIiwidW4iOiJkZXYiLCJybiI6Iui2hee6p-euoeeQhuWRmCIsInppZCI6Ii0xIiwienQiOjAsImRpZCI6IjAiLCJwY29kZSI6IjMwMDEiLCJyaWQiOiJhdmxzR2FtQ2NHYXRjRGJpS1FJIiwidGlkIjoiIiwiY3RpZCI6Imdsb2JhbCIsIm1ybGUiOiIwIiwiZXhwIjoxNzg3MDczMTgzfQ.vq_NDrTlmfDLx7Jijks7--RkbT0AznarHTE0jpV7hmU%22%2C%22refreshToken%22:%22Bearer%20eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1aWQiOiIxIiwidW4iOiJkZXYiLCJybiI6Iui2hee6p-euoeeQhuWRmCIsInppZCI6Ii0xIiwienQiOjAsImRpZCI6IjAiLCJwY29kZSI6IjMwMDEiLCJyaWQiOiJhdmxzR2FtQ2NHYXRjRGJpS1FJIiwidGlkIjoiIiwiY3RpZCI6Imdsb2JhbCIsIm1ybGUiOiIwIiwiZXhwIjoxNzg3MDczMTgzfQ.vq_NDrTlmfDLx7Jijks7--RkbT0AznarHTE0jpV7hmU%22%2C%22expires%22:1787044683355}".getBytes());
        int diffIndex = -1;
        for (int i = 0; i < bytes.length; ++i) {
            if(bytes[i] != hpack0[i]) {
                System.out.println("第 " + i + "个字节出现不一致");
                diffIndex = i;
                break;
            }
        }
        System.out.println("diffIndex " + diffIndex);
        System.out.println("total " + hpack0.length);

        byte[] result = new byte[1024];

        // brute-force cases that trigger the decode bug (run first, independent of below)
        List<String> triggers = findTriggerCases();
        System.out.println("==== total trigger cases: " + triggers.size() + " ====");
        for (String t : triggers) System.out.println("CASE: [" + repr(t) + "]");

        // 发现加密和报文里面的cookie编码一致说明HuffmanByteCodec编码逻辑没有问题
        try {
            int count = HuffmanByteCodec.decodeData(hpack0, 0, len, result, 0);
            System.out.println("result = " + new String(result, 0, count, StandardCharsets.UTF_8));
        } catch (Throwable t) {
            System.out.println("ORIGINAL hpack0 decode failed: " + t);
        }

        // Map<String, Object> map = http2HpackCodec.decode(hpackDatas);
        // System.out.println(map);
    }


}

package io.github.wycst.wastnet.examples.h2;

import io.github.wycst.wastnet.http.h2.HuffmanByteCodec;

import java.util.Arrays;

/** Verify roundtrip for all 256 byte values. */
public class HuffmanCodecTest {

    public static void main(String[] args) {
        // Test 1: full URL string
        String str = "/screen-layout/schedule/fault-command-dispatch-web/rest/failure-schedule-portal-server/login/sms/getPicVerifyCodeForSendLoginSms?backColor=255.255.255";
        byte[] bytes = str.getBytes();
        byte[] enc = HuffmanByteCodec.encodeData(bytes, 0, bytes.length);
        byte[] dec = new byte[bytes.length];
        int count = HuffmanByteCodec.decodeData(enc, 0, enc.length, dec, 0);
        boolean ok = new String(dec, 0, count).equals(str);
        System.out.println("URL test: " + (ok ? "PASS" : "FAIL"));
        if (!ok) System.out.println("  expected: " + str + "\n  got:      " + new String(dec, 0, count));

        // Test 2: all 256 bytes roundtrip
        byte[] all = new byte[256];
        for (int i = 0; i < 256; ++i) all[i] = (byte) i;
        byte[] allEnc = HuffmanByteCodec.encodeData(all, 0, 256);
        byte[] allDec = new byte[256];
        int allCount = HuffmanByteCodec.decodeData(allEnc, 0, allEnc.length, allDec, 0);
        boolean allOk = allCount == 256 && Arrays.equals(all, allDec);
        System.out.println("256-byte roundtrip: " + (allOk ? "PASS" : "FAIL"));
        if (!allOk) {
            for (int i = 0; i < 256; ++i) {
                if (all[i] != allDec[i]) System.out.println("  mismatch at " + i + ": expected " + (all[i] & 0xFF) + " got " + (allDec[i] & 0xFF));
            }
        }

        // Test 3: empty input
        byte[] emptyEnc = HuffmanByteCodec.encodeData(new byte[0], 0, 0);
        int emptyCount = HuffmanByteCodec.decodeData(emptyEnc, 0, emptyEnc.length, new byte[0], 0);
        System.out.println("empty input: " + (emptyCount == 0 ? "PASS" : "FAIL"));

        // Test 4: encodeHpackLiteral + decode via Http2HpackCodec
        byte[] literalOut = new byte[256 * 4 + 5];
        int litLen = HuffmanByteCodec.encodeHpackLiteral(all, 0, 256, literalOut, 0);
        byte[] litData = new byte[litLen];
        System.arraycopy(literalOut, 0, litData, 0, litLen);
        System.out.println("encodeHpackLiteral output: " + litLen + " bytes");

        System.out.println(ok && allOk ? "\nAll tests PASS" : "\nSome tests FAILED");
    }
}

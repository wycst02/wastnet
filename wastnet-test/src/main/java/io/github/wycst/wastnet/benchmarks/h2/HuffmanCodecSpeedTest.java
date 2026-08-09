package io.github.wycst.wastnet.benchmarks.h2;

import io.github.wycst.wastnet.http.h2.HuffmanByteCodec;

import java.util.Arrays;

public class HuffmanCodecSpeedTest {

    public static void main(String[] args) {
        String source = "/screen-layout/schedule/fault-command-dispatch-web/rest/failure-schedule-portal-server/login/sms/getPicVerifyCodeForSendLoginSms?backColor=255.255.255";
        // source = "/screen-layout";
        byte[] encodedBytes = source.getBytes();
        byte[] targetBytes = new byte[1024];
        int count = 0;
        long startTime = System.currentTimeMillis();
        for (int i = 0; i < 1000000; ++i) {
            count = HuffmanByteCodec.encodeData(encodedBytes, 0, encodedBytes.length, targetBytes, 0);
        }
        long endTime = System.currentTimeMillis();
        System.out.println("Time: " + (endTime - startTime) + "ms");
        System.out.println(Arrays.toString(targetBytes));

    }

}

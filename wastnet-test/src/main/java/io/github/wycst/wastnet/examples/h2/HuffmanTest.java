package io.github.wycst.wastnet.examples.h2;

import io.github.wycst.wastnet.http.h2.HuffmanByteCodec;

public class HuffmanTest {
    public static void main(String[] args) {
        byte[] input = "custom1".getBytes();
        byte[] output = new byte[input.length * 2];
        
        int encodedLen = HuffmanByteCodec.encodeData(input, 0, input.length, output, 0);
        
        System.out.print("Hex: ");
        for (int i = 0; i < encodedLen; ++i) {
            System.out.printf("%02X ", output[i] & 0xFF);
        }
        System.out.println();
        
        // Verify by decoding
        byte[] decoded = new byte[encodedLen * 2];
        int decodedLen = HuffmanByteCodec.decodeData(output, 0, encodedLen, decoded, 0);
        System.out.println("Decoded: " + new String(decoded, 0, decodedLen));
    }
}

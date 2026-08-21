package io.github.wycst.wastnet.http.h2;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Brute-force exhaustive decode test over the FULL 256-byte value space for
 * lengths 1, 2 and 3. The 3-nested loop over (a,b,c) additionally exercises
 * the 1-len and 2-len prefixes in-place, so all three lengths are covered in
 * a single pass. Duplicate inputs are skipped via a BitSet keyed by the input.
 *
 * <p>Memory-conscious: main loop uses no boxed collections. Failures are stored
 * in a List (3 bytes per case).</p>
 */
public class HpackHuffman3LenBruteForceTest {

    static final BitSet seen = new BitSet(1 << 26); // covers key range [0, 0x1FFFFFF]
    static final List<byte[]> failures = new ArrayList<>();
    static final byte[] decBuf = new byte[11];

    public static void main(String[] args) {
        long t0 = System.currentTimeMillis();

        int[] total = {0, 0, 0};
        int[] ok = {0, 0, 0};
        int[] fail = {0, 0, 0};
        int[] distB0 = new int[256];
        int[] distB1 = new int[256];
        int[] distB2 = new int[256];
        int[] errKind = new int[4]; // 0=length, 1=byte, 2=ex-null, 3=ex-msg

        byte[] plain = new byte[3];

        for (int a = 0; a < 256; ++a) {
            plain[0] = (byte) a;
            runCase(plain, 1, total, ok, fail, distB0, distB1, distB2, errKind);

            for (int b = 0; b < 256; ++b) {
                plain[1] = (byte) b;
                runCase(plain, 2, total, ok, fail, distB0, distB1, distB2, errKind);

                for (int c = 0; c < 256; ++c) {
                    plain[2] = (byte) c;
                    runCase(plain, 3, total, ok, fail, distB0, distB1, distB2, errKind);
                }
            }
        }

        long elapsed = System.currentTimeMillis() - t0;
        int tAll = total[0] + total[1] + total[2];
        int oAll = ok[0] + ok[1] + ok[2];
        int fAll = fail[0] + fail[1] + fail[2];

        PrintStream out = System.out;
        out.println("=========================================");
        out.println("LENGTH    TOTAL      OK        FAIL");
        out.println("  1-len : " + pad(total[0]) + pad(ok[0]) + fail[0]);
        out.println("  2-len : " + pad(total[1]) + pad(ok[1]) + fail[1]);
        out.println("  3-len : " + pad(total[2]) + pad(ok[2]) + fail[2]);
        out.println("  ALL   : " + pad(tAll) + pad(oAll) + fAll);
        out.println("Elapsed: " + elapsed + " ms");
        out.println("Error kinds: LENGTH=" + errKind[0] + " BYTE=" + errKind[1]
                + " EX(null)=" + errKind[2] + " EX(msg)=" + errKind[3]);
        out.println("=========================================");

        out.println("DIST b0 (top):");
        printTopBytes(out, distB0);
        out.println("DIST b1 (top):");
        printTopBytes(out, distB1);
        out.println("DIST b2 (top):");
        printTopBytes(out, distB2);

        out.println("-----------------------------------------");
        out.println("FAIL CASES (hex bytes): " + fAll);
        out.println("-----------------------------------------");
        StringBuilder sb = new StringBuilder();
        for (byte[] f : failures) {
            sb.append(String.format("%02x%02x%02x%n", f[0] & 0xFF, f[1] & 0xFF, f[2] & 0xFF));
        }
        out.print(sb);
    }

    static String pad(int v) {
        return String.format("%-10d", v);
    }

    static void runCase(byte[] plain, int len, int[] total, int[] ok, int[] fail,
                        int[] distB0, int[] distB1, int[] distB2, int[] errKind) {
        // dedup key: length in high byte, then up to 3 input bytes
        long key = len;
        for (int i = 0; i < len; ++i) key = (key << 8) | (plain[i] & 0xFF);
        int k = (int) key;
        if (seen.get(k)) return;
        seen.set(k);

        ++total[len - 1];
        byte[] enc = HuffmanByteCodec.encodeData(plain, 0, len);
        int n;
        boolean bad = false;
        int kind = 0;
        try {
            n = HuffmanByteCodec.decodeData(enc, 0, enc.length, decBuf, 0);
            if (n != len) {
                kind = 0;
                bad = true;
            } else {
                for (int i = 0; i < len; ++i) {
                    if (decBuf[i] != plain[i]) {
                        kind = 1;
                        bad = true;
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            kind = (t.getMessage() == null) ? 2 : 3;
            bad = true;
            n = -1;
        }

        if (!bad) {
            ++ok[len - 1];
            return;
        }

        ++fail[len - 1];
        failures.add(new byte[]{plain[0], plain[1], plain[2]});
        if (len >= 1) distB0[plain[0] & 0xFF]++;
        if (len >= 2) distB1[plain[1] & 0xFF]++;
        if (len >= 3) distB2[plain[2] & 0xFF]++;
        errKind[kind]++;
    }

    static void printTopBytes(PrintStream out, int[] dist) {
        boolean[] used = new boolean[256];
        for (int k = 0; k < 256; ++k) {
            int best = -1, bestV = -1;
            for (int i = 0; i < 256; ++i) {
                if (!used[i] && dist[i] > bestV) {
                    best = i;
                    bestV = dist[i];
                }
            }
            if (best < 0 || bestV == 0) break;
            used[best] = true;
            out.println("  0x" + String.format("%02x", best) + " -> " + bestV);
        }
    }
}

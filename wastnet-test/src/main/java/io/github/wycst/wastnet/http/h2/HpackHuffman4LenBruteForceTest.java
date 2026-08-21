package io.github.wycst.wastnet.http.h2;

/**
 * Brute-force 4-len exhaustive decode test over all printable ASCII (95 chars, 0x20..0x7E).
 *
 * <p>Subset = space + !..~ (digits, letters, common URL/header symbols incl. $ &amp; * ! _).
 * Total: 95^4 = 78,074,896 distinct inputs.</p>
 *
 * <p>Memory-conscious: no boxed collections, no per-case allocations. Failures are
 * stored compactly (4 bytes per case). 3 fixed int[256] distribution arrays (1KB).</p>
 *
 * <p>Companion tests: HpackHuffman3LenBruteForceTest (256^3 full byte space),
 * HpackErrorTest2 (mixed-length).</p>
 */
public class HpackHuffman4LenBruteForceTest {

    // printable ASCII 0x20..0x7E = 95 chars
    static final int FIRST = 0x20;
    static final int LAST = 0x7E; // inclusive
    static final int N = LAST - FIRST + 1; // 95

    public static void main(String[] args) {
        long t0 = System.currentTimeMillis();

        int total = 0, ok = 0, fail = 0;

        // Compact failure storage: 4 bytes per case.
        byte[] failBytes = new byte[8192];
        int failLen = 0;
        int failCap = failBytes.length;

        int[] distB0 = new int[256];
        int[] distB1 = new int[256];
        int[] distB2 = new int[256];
        int[] distB3 = new int[256];
        int[] errKind = new int[4]; // 0=length, 1=byte, 2=ex-null, 3=ex-msg

        byte[] plain = new byte[4];
        byte[] dec = new byte[12]; // > worst-case encoded length for 4 bytes

        for (int a = FIRST; a <= LAST; ++a) {
            plain[0] = (byte) a;
            for (int b = FIRST; b <= LAST; ++b) {
                plain[1] = (byte) b;
                for (int c = FIRST; c <= LAST; ++c) {
                    plain[2] = (byte) c;
                    for (int d = FIRST; d <= LAST; ++d) {
                        plain[3] = (byte) d;
                        ++total;

                        byte[] enc = HuffmanByteCodec.encodeData(plain, 0, 4);
                        int n;
                        boolean bad = false;
                        int kind = 0;
                        try {
                            n = HuffmanByteCodec.decodeData(enc, 0, enc.length, dec, 0);
                            if (n != 4) {
                                kind = 0;
                                bad = true;
                            } else {
                                for (int i = 0; i < 4; ++i) {
                                    if (dec[i] != plain[i]) {
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
                            ++ok;
                            continue;
                        }

                        ++fail;
                        if (failLen + 4 > failCap) {
                            int newCap = failCap * 2;
                            while (newCap < failLen + 4) newCap *= 2;
                            byte[] nb = new byte[newCap];
                            System.arraycopy(failBytes, 0, nb, 0, failLen);
                            failBytes = nb;
                            failCap = newCap;
                        }
                        failBytes[failLen] = plain[0];
                        failBytes[failLen + 1] = plain[1];
                        failBytes[failLen + 2] = plain[2];
                        failBytes[failLen + 3] = plain[3];
                        failLen += 4;
                        distB0[a & 0xFF]++;
                        distB1[b & 0xFF]++;
                        distB2[c & 0xFF]++;
                        distB3[d & 0xFF]++;
                        errKind[kind]++;
                    }
                }
            }
        }

        long elapsed = System.currentTimeMillis() - t0;

        System.out.println("=========================================");
        System.out.println("TOTAL : " + total);
        System.out.println("OK    : " + ok);
        System.out.println("FAIL  : " + fail);
        System.out.println("Elapsed: " + elapsed + " ms");
        System.out.println("Error kinds: LENGTH=" + errKind[0] + " BYTE=" + errKind[1]
                + " EX(null)=" + errKind[2] + " EX(msg)=" + errKind[3]);
        System.out.println("=========================================");

        System.out.println("DIST b0 (top):");   printTopBytes(distB0);
        System.out.println("DIST b1 (top):");   printTopBytes(distB1);
        System.out.println("DIST b2 (top):");   printTopBytes(distB2);
        System.out.println("DIST b3 (top):");   printTopBytes(distB3);

        System.out.println("-----------------------------------------");
        System.out.println("FAIL CASES (hex bytes): " + fail);
        System.out.println("-----------------------------------------");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < failLen; i += 4) {
            sb.append(String.format("%02x%02x%02x%02x%n",
                    failBytes[i] & 0xFF,
                    failBytes[i + 1] & 0xFF,
                    failBytes[i + 2] & 0xFF,
                    failBytes[i + 3] & 0xFF));
        }
        System.out.print(sb);
    }

    static void printTopBytes(int[] dist) {
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
            System.out.println("  0x" + String.format("%02x", best) + " -> " + bestV);
        }
    }
}

package io.github.wycst.wastnet.http.h2;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Exhaustive decode robustness test for HuffmanByteCodec.decodeData.
 *
 * <p>encodeData is assumed 100% correct (verified against RFC 7541). So any
 * string that can be encoded must decode back byte-for-byte. Any exception or
 * mismatch from decodeData is a decode bug. This class harvests all such cases
 * so they can be fixed in one pass.</p>
 */
public class HpackErrorTest2 {

    // charset: digits, letters, and the long-code symbols whose Huffman codes
    // are >= 11 bits (these force the multi-byte decode path and EOS-padding edge).
    static final String CHARS =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ.-_/{}%:=\\\" ~|{[^`";

    static final char[] CA = CHARS.toCharArray();

    // collected failing cases: {inputString, encHex, errorOrMismatch}
    static final List<String[]> fails = new ArrayList<>();
    static final java.util.Set<String> seen = new java.util.HashSet<>();
    static int totalChecked = 0;
    static int okCount = 0;

    public static void main(String[] args) {
        // 1) every single byte value 0..255 (covers all short codes + EOS edge)
        for (int v = 0; v < 256; ++v) {
            check(new byte[]{(byte) v});
        }
        // 2) long-code symbol x every char (2-len): multi-byte path + trailing padding
        String longSym = "~|{[^`}";
        for (char a : longSym.toCharArray()) {
            for (char b : CA) {
                check(( "" + a + b).getBytes(StandardCharsets.US_ASCII));
            }
        }
        // 3) long-code symbols clustered (3~4 len): result==value still needs more bytes
        String[] clusters = {"~~~", "}}}", "|||", "```", "~~}", "~}~", "}~}", "[[[", "{{}", "}^~", "`|~"};
        for (String s : clusters) check(s.getBytes(StandardCharsets.US_ASCII));
        // 4) brute-force 2-len over full charset (covers padding-tail combos)
        for (char a : CA) {
            for (char b : CA) {
                check(("" + a + b).getBytes(StandardCharsets.US_ASCII));
            }
        }
        // 5) brute-force 3-len over a reduced set (long-code + digits) to widen
        String reduced = "0123456789~|{[^`}\\";
        char[] rc = reduced.toCharArray();
        for (char a : rc) {
            for (char b : rc) {
                for (char c : rc) {
                    check(("" + a + b + c).getBytes(StandardCharsets.US_ASCII));
                }
            }
        }
        // 6) tail-byte cases: for EVERY byte value 0..255, make it the LAST byte of a
        // case, so every special byte's trailing-padding / EOS edge is exercised.
        // prefixes: long-code symbols + digits + a few normals, to vary the path
        String prefixStr = "~|{[^`}0123456789abcde";
        byte[] pb = prefixStr.getBytes(StandardCharsets.US_ASCII);
        for (int tb = 0; tb < 256; ++tb) {
            // 1-len: just the tail byte alone
            check(new byte[]{(byte) tb});
            // 2-len: every prefix + tail byte
            for (byte p : pb) {
                check(new byte[]{p, (byte) tb});
            }
            // 3-len: long-code symbol + digit + tail byte (forces multi-byte path then tail)
            for (char a : longSym.toCharArray()) {
                for (char d : "0123456789".toCharArray()) {
                    check(new byte[]{(byte) a, (byte) d, (byte) tb});
                }
            }
        }

        // 7) length-5..30 random case: fixed-seed random over CA, 200 per length.
        // 长输入特别有助于暴露多字节尾位的 padding / EOS edge bug.
        {
            java.util.Random rnd = new java.util.Random(0xC0DEBA5EL);
            int[] lens = {5, 6, 7, 8, 9, 10, 11, 12, 15, 20, 25, 30};
            for (int len : lens) {
                for (int n = 0; n < 200; ++n) {
                    char[] buf = new char[len];
                    for (int i = 0; i < len; ++i) {
                        buf[i] = CA[rnd.nextInt(CA.length)];
                    }
                    check(new String(buf).getBytes(StandardCharsets.US_ASCII));
                }
            }
        }

        // report
        System.out.println("=========================================");
        System.out.println("TOTAL CHECKED (distinct inputs): " + totalChecked);
        System.out.println("OK : " + okCount);
        System.out.println("FAIL (raw, with dup): " + fails.size());
        // dedupe by input case
        java.util.LinkedHashSet<String> distinct = new java.util.LinkedHashSet<>();
        int nullCnt = 0, numCnt = 0;
        for (String[] f : fails) {
            distinct.add(f[0]);
            if (f[2].contains("Exception: null")) ++nullCnt;
            else ++numCnt;
        }
        System.out.println("DISTINCT FAILS: " + distinct.size());
        System.out.println("  L193 AIOOBE(null): " + nullCnt);
        System.out.println("  L227 AIOOBE(num) : " + numCnt);
        // tail-char distribution of distinct failing cases
        java.util.Map<Character, Integer> tailDist = new java.util.HashMap<>();
        for (String key : distinct) {
            char tail = key.charAt(key.length() - 1);
            tailDist.put(tail, tailDist.getOrDefault(tail, 0) + 1);
        }
        System.out.println("TAIL DISTRIBUTION (distinct):");
        List<java.util.Map.Entry<Character, Integer>> tails =
                new ArrayList<>(tailDist.entrySet());
        tails.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        for (java.util.Map.Entry<Character, Integer> e : tails) {
            char c = e.getKey();
            String disp = (c >= 32 && c < 127) ? ("" + c) : ("\\" + (int) c);
            System.out.println("  tail='" + disp + "' -> " + e.getValue());
        }
        // length distribution among failing distinct cases
        java.util.Map<Integer, Integer> lenDist = new java.util.HashMap<>();
        for (String key : distinct) {
            int L = key.length();
            lenDist.put(L, lenDist.getOrDefault(L, 0) + 1);
        }
        System.out.println("LENGTH DISTRIBUTION (distinct):");
        List<java.util.Map.Entry<Integer, Integer>> les =
                new ArrayList<>(lenDist.entrySet());
        les.sort((a, b) -> a.getKey().compareTo(b.getKey()));
        for (java.util.Map.Entry<Integer, Integer> e : les) {
            System.out.println("  len=" + e.getKey() + " -> " + e.getValue());
        }
        System.out.println("-----------------------------------------");
        // targeted tail-byte cases: '?'=63, '!'=33, 176, 19
        int[] targetTails = {63, 33, 176, 19};
        System.out.println("TARGETED TAIL-BYTE FAILS:");
        for (String[] f : fails) {
            String key = f[0];
            int last = key.charAt(key.length() - 1) & 0xFF;
            boolean hit = false;
            for (int tt : targetTails) if (last == tt) { hit = true; break; }
            if (hit) System.out.println("case=" + repr(f[0]) + "  enc=" + f[1] + "  -> " + f[2]);
        }
        System.out.println("-----------------------------------------");
        for (String[] f : fails) {
            System.out.println("case=" + repr(f[0]) + "  enc=" + f[1] + "  -> " + f[2]);
        }
        System.out.println("=========================================");
        if (fails.isEmpty()) {
            System.out.println("ALL DECODE OK");
        }
    }

    static void check(byte[] plain) {
        String key = new String(plain, StandardCharsets.ISO_8859_1);
        if (!seen.add(key)) return; // dedupe identical inputs
        ++totalChecked;
        byte[] enc = HuffmanByteCodec.encodeData(plain, 0, plain.length);
        byte[] dec = new byte[plain.length + 8];
        int n;
        try {
            n = HuffmanByteCodec.decodeData(enc, 0, enc.length, dec, 0);
        } catch (Throwable t) {
            fails.add(new String[]{new String(plain, StandardCharsets.ISO_8859_1), toHex(enc),
                    t.getClass().getSimpleName() + ": " + t.getMessage()});
            return;
        }
        // compare decoded bytes
        if (n != plain.length) {
            fails.add(new String[]{new String(plain, StandardCharsets.ISO_8859_1), toHex(enc),
                    "LENGTH MISMATCH decoded=" + n + " expected=" + plain.length});
            return;
        }
        for (int i = 0; i < plain.length; ++i) {
            if (dec[i] != plain[i]) {
                fails.add(new String[]{new String(plain, StandardCharsets.ISO_8859_1), toHex(enc),
                        "BYTE MISMATCH at " + i + " got=" + (dec[i] & 0xFF) + " exp=" + (plain[i] & 0xFF)});
                return;
            }
        }
        ++okCount;
    }

    static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xFF));
        return sb.toString();
    }

    static String repr(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); ++i) {
            char c = s.charAt(i);
            if (c >= 32 && c < 127) sb.append(c);
            else sb.append('\\').append((int) c);
        }
        return sb.toString();
    }
}

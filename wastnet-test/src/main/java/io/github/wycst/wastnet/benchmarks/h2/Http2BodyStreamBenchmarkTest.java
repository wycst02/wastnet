package io.github.wycst.wastnet.benchmarks.h2;

import io.github.wycst.wastnet.http.h2.Http2BodyInputStream;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;

/**
 * Http2BodyInputStream 性能基准测试。
 * 输出统计：最小值、P50、P90、P99、最大值、平均值、标准差。
 */
public class Http2BodyStreamBenchmarkTest {

    public static void main(String[] args) throws Exception {

        System.out.println("===== Http2BodyInputStream Benchmark =====");
        System.out.println("JVM: " + System.getProperty("java.version") + "  "
                + System.getProperty("sun.arch.data.model") + "-bit\n");

        // ============================================================
        // 场景 1：顺序读写
        // ============================================================
        System.out.println("--- 场景 1：顺序读写 (read-after-feed) ---\n");
        int[] dataSizes = new int[]{1024, 64 * 1024, 512 * 1024};
        int[] frameSizes = new int[]{4096, 16384, 65536};
        for (int dataSize : dataSizes) {
            int cap = Math.max(dataSize, 65536);
            int iter = iterCount(cap, 256 * 1024 * 1024L);
            for (int frameSize : frameSizes) {
                long[] samples = benchmarkSequential(dataSize, frameSize, cap, iter);
                printStats("seq", dataSize, frameSize, new long[]{dataSize}, samples);
            }
        }

        // ============================================================
        // 场景 2：并发 SPSC
        // ============================================================
        System.out.println("\n--- 场景 2：并发 SPSC 读写 ---\n");
        for (int dataSize : new int[]{512 * 1024, 2 * 1024 * 1024, 10 * 1024 * 1024}) {
            for (int frameSize : new int[]{4096, 16384, 65536}) {
                long[] samples = benchmarkConcurrent(dataSize, frameSize);
                printStats("spsc", dataSize, frameSize, new long[]{dataSize}, samples);
            }
        }

        // ============================================================
        // 场景 3：小帧压力
        // ============================================================
        System.out.println("\n--- 场景 3：小帧压力 (frame=256B) ---\n");
        for (int dataSize : new int[]{512 * 1024, 2 * 1024 * 1024}) {
            long[] samples = benchmarkConcurrent(dataSize, 256);
            printStats("spsc", dataSize, 256, new long[]{dataSize}, samples);
        }

        // ============================================================
        // 场景 4：空载 feed
        // ============================================================
        System.out.println("\n--- 场景 4：空载 feed (ended 后调用) ---\n");
        int iterations = 1_000_000;
        long[] samples = benchmarkEmptyFeed(iterations);
        printStats("feed", 0, 0, new long[]{}, samples, iterations);
    }

    // ---- 工具方法 ----

    static int iterCount(int allocPerCall, long maxAlloc) {
        long n = maxAlloc / allocPerCall;
        return (int) Math.min(n, 20000);
    }

    static void printStats(String tag, int dataSize, int frameSize, long[] perCallBytes, long[] samples) {
        printStats(tag, dataSize, frameSize, perCallBytes, samples, samples.length);
    }

    static void printStats(String tag, int dataSize, int frameSize, long[] perCallBytes, long[] samples, int n) {
        Arrays.sort(samples);
        long min = samples[0];
        long max = samples[samples.length - 1];
        long p50 = percentile(samples, 50);
        long p90 = percentile(samples, 90);
        long p99 = percentile(samples, 99);
        double sum = 0;
        for (long v : samples) sum += v;
        double avg = sum / n;

        double variance = 0;
        for (long v : samples) {
            double diff = v - avg;
            variance += diff * diff;
        }
        double stddev = Math.sqrt(variance / n);

        long totalBytes = 0;
        for (long b : perCallBytes) totalBytes += b;

        System.out.printf("  %-6s  total=%8dB  frame=%6dB  iter=%5d" +
                        "  min=%6.1f  p50=%6.1f  p99=%6.1f  max=%6.1f  \u03c3=%5.1f  avg=%6.1f MB/s%n",
                tag, dataSize, frameSize, n,
                bytesPerSec(min, totalBytes), bytesPerSec(p50, totalBytes),
                bytesPerSec(p99, totalBytes), bytesPerSec(max, totalBytes),
                bytesPerSec((long) stddev, totalBytes),
                bytesPerSec((long) avg, totalBytes));
    }

    static double bytesPerSec(long nanos, long bytes) {
        if (nanos == 0) return Double.POSITIVE_INFINITY;
        return bytes * 1_000_000_000.0 / nanos / 1024 / 1024;
    }

    static long percentile(long[] sorted, int pct) {
        int idx = (int) Math.ceil(pct / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, idx)];
    }

    // ---- 基准 1：顺序读写 ----

    static long[] benchmarkSequential(int totalSize, int frameSize, int cap, int iterations) throws IOException {
        byte[] data = randomBytes(totalSize);
        byte[] buf = new byte[8192];

        int warmup = Math.min(iterations, 2000);
        for (int i = 0; i < warmup; ++i) {
            Http2BodyInputStream is = new Http2BodyInputStream(new byte[cap], null);
            sequentialRun(is, data, frameSize, buf);
        }

        long[] samples = new long[iterations];
        for (int i = 0; i < iterations; ++i) {
            Http2BodyInputStream is = new Http2BodyInputStream(new byte[cap], null);
            long start = System.nanoTime();
            sequentialRun(is, data, frameSize, buf);
            samples[i] = System.nanoTime() - start;
        }
        return samples;
    }

    static void sequentialRun(Http2BodyInputStream is, byte[] data, int frameSize, byte[] buf) throws IOException {
        int off = 0;
        while (off < data.length) {
            int len = Math.min(frameSize, data.length - off);
            is.feed(data, off, len);
            off += len;
        }
        is.endStream();
        while (is.read(buf, 0, buf.length) != -1) ;
    }

    // ---- 基准 2：并发 SPSC ----

    static long[] benchmarkConcurrent(int totalSize, int frameSize) {
        byte[] data = randomBytes(totalSize);
        int cap = Math.max(totalSize / 4, 65536);
        byte[] buf = new byte[8192];

        int warmup = 30;
        for (int i = 0; i < warmup; ++i) {
            concurrentRun(data, frameSize, cap, buf);
        }

        int count = 15;
        long[] samples = new long[count];
        for (int i = 0; i < count; ++i) {
            long start = System.nanoTime();
            concurrentRun(data, frameSize, cap, buf);
            samples[i] = System.nanoTime() - start;
        }
        return samples;
    }

    static void concurrentRun(byte[] data, int frameSize, int cap, byte[] buf) {
        final Http2BodyInputStream is = new Http2BodyInputStream(new byte[cap], null);
        final CountDownLatch latch = new CountDownLatch(1);

        Thread reader = new Thread(new Runnable() {
            public void run() {
                try {
                    long consumed = 0;
                    while (consumed < data.length) {
                        int n = is.read(buf, 0, buf.length);
                        if (n == -1) break;
                        consumed += n;
                    }
                    latch.countDown();
                } catch (IOException e) {
                    latch.countDown();
                }
            }
        });

        reader.start();
        int fed = 0;
        while (fed < data.length) {
            int len = Math.min(frameSize, data.length - fed);
            is.feed(data, fed, len);
            fed += len;
        }
        is.endStream();

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- 基准 3：空载 feed ----

    static long[] benchmarkEmptyFeed(int iterations) {
        Http2BodyInputStream is = new Http2BodyInputStream(new byte[65536], null);
        is.endStream();

        int warmup = 2000;
        for (int i = 0; i < warmup; ++i) {
            is.feed(new byte[0], 0, 0);
        }

        long[] samples = new long[iterations];
        for (int i = 0; i < iterations; ++i) {
            long start = System.nanoTime();
            is.feed(new byte[0], 0, 0);
            samples[i] = System.nanoTime() - start;
        }
        return samples;
    }

    static byte[] randomBytes(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; ++i) {
            data[i] = (byte) (i & 0xFF);
        }
        return data;
    }
}

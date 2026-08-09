package io.github.wycst.wastnet.benchmarks.websocket;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark matching the two WebSocket unmask code paths in
 * {@code WebSocketDecoder.unmask}:
 * <ol>
 *   <li>{@link #byteLoop}  — 8-byte unrolled byte XOR</li>
 *   <li>{@link #bbNative}  — ByteBuffer native-order getLong/putLong XOR</li>
 * </ol>
 * <p>
 * XOR is self-inverse, so no clone needed.
 * <p>
 * Results (2MB payload, ops/sec):
 * <pre>
 * JDK   8-byteLoop  bbNative    ratio
 *  8       4,205       1,502     0.36x
 * 17       4,306      24,935     5.8x
 * 25       4,224      38,013     9.0x
 * </pre>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread)
public class WebSocketUnmaskBenchmark {

    private static final int SIZE = 2 * 1024 * 1024;

    private byte[] data;
    private byte m0, m1, m2, m3;
    private long mask64;

    @Setup
    public void setup() {
        data = new byte[SIZE];
        for (int i = 0; i < data.length; ++i) data[i] = (byte) (i & 0xFF);
        m0 = 0x3A; m1 = 0x5C; m2 = (byte) 0xF1; m3 = 0x27;
        byte[] maskBytes = {m0, m1, m2, m3, m0, m1, m2, m3};
        mask64 = ByteBuffer.wrap(maskBytes).order(ByteOrder.nativeOrder()).getLong(0);
    }

    @Benchmark
    public void byteLoop(Blackhole bh) {
        unmaskByte8(data, m0, m1, m2, m3);
        bh.consume(data);
    }

    @Benchmark
    public void bbNative(Blackhole bh) {
        unmaskBbNative(data, mask64, m0, m1, m2, m3);
        bh.consume(data);
    }

    // ==================== Implementations ====================

    static void unmaskByte8(byte[] data, byte m0, byte m1, byte m2, byte m3) {
        int len = data.length, aligned = len & ~7;
        for (int i = 0; i < aligned; i += 8) {
            data[i]     ^= m0; data[i + 1] ^= m1;
            data[i + 2] ^= m2; data[i + 3] ^= m3;
            data[i + 4] ^= m0; data[i + 5] ^= m1;
            data[i + 6] ^= m2; data[i + 7] ^= m3;
        }
        if (aligned == len) return;
        switch (len & 7) {
            case 7: data[aligned + 6] ^= m2;
            case 6: data[aligned + 5] ^= m1;
            case 5: data[aligned + 4] ^= m0;
            case 4: data[aligned + 3] ^= m3;
            case 3: data[aligned + 2] ^= m2;
            case 2: data[aligned + 1] ^= m1;
            case 1: data[aligned]     ^= m0;
        }
    }

    static void unmaskBbNative(byte[] data, long mask, byte m0, byte m1, byte m2, byte m3) {
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.nativeOrder());
        int len = data.length, aligned = len & ~7;
        for (int i = 0; i < aligned; i += 8) bb.putLong(i, bb.getLong(i) ^ mask);
        if (aligned == len) return;
        switch (len & 7) {
            case 7: data[aligned + 6] ^= m2;
            case 6: data[aligned + 5] ^= m1;
            case 5: data[aligned + 4] ^= m0;
            case 4: data[aligned + 3] ^= m3;
            case 3: data[aligned + 2] ^= m2;
            case 2: data[aligned + 1] ^= m1;
            case 1: data[aligned]     ^= m0;
        }
    }

    public static void main(String[] args) throws Exception {
        new Runner(new OptionsBuilder()
                .include(WebSocketUnmaskBenchmark.class.getSimpleName())
                .warmupIterations(2).warmupTime(TimeValue.seconds(2))
                .measurementIterations(5).measurementTime(TimeValue.seconds(2))
                .forks(1)
                .build()).run();
    }
}

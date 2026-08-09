package io.github.wycst.wastnet.benchmarks.h2;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

/**
 * 对比 WINDOW_UPDATE frame 发送的两种策略：
 * <ul>
 *   <li>synchronized + 复用预分配 ByteBuffer（当前实现）</li>
 *   <li>每次都分配新 ByteBuffer + 无锁</li>
 * </ul>
 * <p>
 * 两种方案做完全相同的事情，唯一区别是有无 synchronized + 对象是复用还是新建。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Fork(2)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
public class WindowUpdateFrameBenchmark {

    private static final int WINDOW_LEN = 65536;
    private static final byte STREAM_PAYLOAD_LENGTH = 4;
    private static final byte WINDOW_UPDATE_TYPE = 8;

    /** 当前实现：预分配 + synchronized */
    private final ByteBuffer sharedFrame = ByteBuffer.allocate(13);

    @Setup
    public void setup() {
        sharedFrame.put(2, STREAM_PAYLOAD_LENGTH);
        sharedFrame.put(3, WINDOW_UPDATE_TYPE);
    }

    // ==================== 单帧 WU ====================

    @Benchmark
    public int syncReuse(Blackhole bh) {
        synchronized (sharedFrame) {
            sharedFrame.putInt(5, WINDOW_LEN);
            sharedFrame.clear();
            bh.consume(sharedFrame);
            return WINDOW_LEN;
        }
    }

    @Benchmark
    public int allocate(Blackhole bh) {
        ByteBuffer frame = ByteBuffer.allocate(13);
        frame.put(2, STREAM_PAYLOAD_LENGTH);
        frame.put(3, WINDOW_UPDATE_TYPE);
        frame.putInt(5, WINDOW_LEN);
        frame.clear();
        bh.consume(frame);
        return WINDOW_LEN;
    }

    // ==================== 双帧 WU（pair） ====================

    @Benchmark
    public int syncReusePair(Blackhole bh) {
        synchronized (sharedFrame) {
            sharedFrame.putInt(9, WINDOW_LEN);
            ByteBuffer pair = ByteBuffer.allocate(26);
            pair.put(sharedFrame.array(), 0, 13).put(sharedFrame.array(), 0, 13);
            pair.putInt(5, 0).putInt(18, 1).flip();
            bh.consume(pair);
            return WINDOW_LEN;
        }
    }

    @Benchmark
    public int allocatePair(Blackhole bh) {
        ByteBuffer connWu = ByteBuffer.allocate(13);
        connWu.put(2, STREAM_PAYLOAD_LENGTH);
        connWu.put(3, WINDOW_UPDATE_TYPE);
        connWu.putInt(9, WINDOW_LEN);

        ByteBuffer pair = ByteBuffer.allocate(26);
        pair.put(connWu.array(), 0, 13).put(connWu.array(), 0, 13);
        pair.putInt(5, 0).putInt(18, 1).flip();
        bh.consume(pair);
        return WINDOW_LEN;
    }

    public static void main(String[] args) throws Exception {
        Options opt = new OptionsBuilder()
                .include(WindowUpdateFrameBenchmark.class.getSimpleName())
                .build();
        new Runner(opt).run();
    }
}

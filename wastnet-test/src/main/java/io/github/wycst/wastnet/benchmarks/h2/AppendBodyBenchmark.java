package io.github.wycst.wastnet.benchmarks.h2;

import io.github.wycst.wastnet.http.HttpBuf;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 对比 Http2Stream.appendBody 改造前后性能。
 * <p>
 * 模拟 N 帧 × 16KB 累积到指定大小的场景。
 * <p>
 * 用法：java ... AppendBodyBenchmark [-fs 16384] [-fc 128] [-f 1] [-wi 3] [-mi 5] [-wt 2] [-mt 2]
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
public class AppendBodyBenchmark {

    private final int frameSize;
    private final int frameCount;
    private final byte[][] frames;

    public AppendBodyBenchmark() {
        this.frameSize = Integer.getInteger("bench.frameSize", 16384);
        this.frameCount = Integer.getInteger("bench.frameCount", 128);
        this.frames = new byte[frameCount][frameSize];
        for (int i = 0; i < frameCount; ++i) {
            Arrays.fill(frames[i], (byte) (i & 0xFF));
        }
    }

    // ==================== 方案一：原版 Arrays.copyOf 实时拷贝 ====================

    static class CopyOfAppender {
        byte[] bodyData = new byte[0];

        void append(byte[] payload, int offset, int len) {
            byte[] newBody = Arrays.copyOf(bodyData, bodyData.length + len);
            System.arraycopy(payload, offset, newBody, bodyData.length, len);
            bodyData = newBody;
        }
    }

    @Benchmark
    public void copyOfAppend(Blackhole bh) {
        CopyOfAppender appender = new CopyOfAppender();
        for (int i = 0; i < frameCount; ++i) {
            appender.append(frames[i], 0, frameSize);
        }
        bh.consume(appender.bodyData);
    }

    // ==================== 方案二：List<byte[]> 追加 + 最后一次性合并 ====================

    static class ListAppender {
        List<byte[]> chunks = new ArrayList<>();
        int totalLength;

        void append(byte[] payload, int offset, int len) {
            byte[] chunk = new byte[len];
            System.arraycopy(payload, offset, chunk, 0, len);
            chunks.add(chunk);
            totalLength += len;
        }

        byte[] toArray() {
            if (chunks.isEmpty()) return new byte[0];
            if (chunks.size() == 1) return chunks.get(0);
            byte[] result = new byte[totalLength];
            int off = 0;
            for (byte[] chunk : chunks) {
                System.arraycopy(chunk, 0, result, off, chunk.length);
                off += chunk.length;
            }
            return result;
        }
    }

    @Benchmark
    public void listAppendThenMerge(Blackhole bh) {
        ListAppender appender = new ListAppender();
        for (int i = 0; i < frameCount; ++i) {
            appender.append(frames[i], 0, frameSize);
        }
        bh.consume(appender.toArray());
    }

    // ==================== 方案三：List<byte[]> 只追加不做最终合并（流式场景） ====================

    @Benchmark
    public void listAppendOnly(Blackhole bh) {
        ListAppender appender = new ListAppender();
        for (int i = 0; i < frameCount; ++i) {
            appender.append(frames[i], 0, frameSize);
        }
        bh.consume(appender.chunks.size());
    }

    // ==================== 方案四：ByteArrayOutputStream 式 2 次幂扩容 ====================

    static class DoublingAppender {
        byte[] buf = new byte[32];  // 初始小容量
        int count;

        void append(byte[] payload, int offset, int len) {
            int newCount = count + len;
            if (newCount > buf.length) {
                // 扩容到下一个 2 次幂
                int newCapacity = buf.length;
                while (newCapacity < newCount) {
                    newCapacity <<= 1;
                }
                buf = Arrays.copyOf(buf, newCapacity);
            }
            System.arraycopy(payload, offset, buf, count, len);
            count = newCount;
        }

        byte[] toByteArray() {
            return /*count == buf.length ? buf :*/ Arrays.copyOf(buf, count);
        }
    }

    @Benchmark
    public void doublingAppend(Blackhole bh) {
        DoublingAppender appender = new DoublingAppender();
        for (int i = 0; i < frameCount; ++i) {
            appender.append(frames[i], 0, frameSize);
        }
        bh.consume(appender.toByteArray());
    }

    @Benchmark
    public void _httpBuf(Blackhole bh) {
        HttpBuf appender = HttpBuf.of(32);
        for (int i = 0; i < frameCount; ++i) {
            appender.append(frames[i], 0, frameSize);
        }
        bh.consume(appender.toBytes());
    }

    public static void main(String[] args) throws RunnerException {
        int warmupIterations = 3;
        int measurementIterations = 5;
        int warmupTime = 2;
        int measurementTime = 2;
        int forks = 1;
        int frameSize = 16384;
        int frameCount = 128;
        for (int i = 0; i < args.length; ++i) {
            switch (args[i]) {
                case "-wi": warmupIterations = Integer.parseInt(args[++i]); break;
                case "-mi": measurementIterations = Integer.parseInt(args[++i]); break;
                case "-wt": warmupTime = Integer.parseInt(args[++i]); break;
                case "-mt": measurementTime = Integer.parseInt(args[++i]); break;
                case "-f": forks = Integer.parseInt(args[++i]); break;
                case "-fs": frameSize = Integer.parseInt(args[++i]); break;
                case "-fc": frameCount = Integer.parseInt(args[++i]); break;
            }
        }
        System.out.printf("帧大小=%d, 帧数=%d, 总数据=%dMB, forks=%d, warmup=%dx%ds, measurement=%dx%ds%n",
                frameSize, frameCount, frameSize * frameCount / 1024 / 1024,
                forks, warmupIterations, warmupTime, measurementIterations, measurementTime);

        System.setProperty("bench.frameSize", String.valueOf(frameSize));
        System.setProperty("bench.frameCount", String.valueOf(frameCount));

        Options opt = new OptionsBuilder()
                .include(AppendBodyBenchmark.class.getSimpleName())
                .forks(forks)
                .warmupIterations(warmupIterations)
                .warmupTime(TimeValue.seconds(warmupTime))
                .measurementIterations(measurementIterations)
                .measurementTime(TimeValue.seconds(measurementTime))
                .build();
        new Runner(opt).run();
    }
}

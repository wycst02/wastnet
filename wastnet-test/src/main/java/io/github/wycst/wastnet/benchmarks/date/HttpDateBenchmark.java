package io.github.wycst.wastnet.benchmarks.date;

import io.github.wycst.wastnet.http.HttpDate;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark comparing {@link HttpDate} vs {@link ZonedDateTime}
 * over a 2-year date range at 1-second intervals.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread)
public class HttpDateBenchmark {

    private static final TimeZone GMT = TimeZone.getTimeZone("GMT");
    private static final ZoneOffset UTC = ZoneOffset.UTC;

    // ~63M ticks (2 years × 365/366 days × 86400 seconds)
    private static final long MS_PER_SECOND = 1000L;
    private static final long MS_PER_YEAR  = 365L * 86400 * 1000;
    private static final long MS_PER_LEAP  = 366L * 86400 * 1000;

    private long start, end;

    @Setup
    public void setup() {
        long now = System.currentTimeMillis();
        start = now - MS_PER_YEAR - MS_PER_LEAP;
        end   = now + MS_PER_YEAR + MS_PER_LEAP;
    }

    @Benchmark
    public void httpDate(Blackhole bh) {
        long sum = 0;
        for (long ts = start; ts <= end; ts += MS_PER_SECOND) {
            HttpDate hd = new HttpDate(ts, GMT);
            sum += hd.getYear() + hd.getMonth() + hd.getDay()
                 + hd.getHourOfDay() + hd.getMinute() + hd.getSecond()
                 + hd.getDayOfWeek();
        }
        bh.consume(sum);
    }

    @Benchmark
    public void zonedDateTime(Blackhole bh) {
        long sum = 0;
        for (long ts = start; ts <= end; ts += MS_PER_SECOND) {
            ZonedDateTime ref = Instant.ofEpochMilli(ts).atZone(UTC);
            sum += ref.getYear() + ref.getMonthValue() + ref.getDayOfMonth()
                 + ref.getHour() + ref.getMinute() + ref.getSecond()
                 + ref.getDayOfWeek().getValue();
        }
        bh.consume(sum);
    }

    public static void main(String[] args) throws Exception {
        new Runner(new OptionsBuilder()
                .include(HttpDateBenchmark.class.getSimpleName())
                .warmupIterations(1).warmupTime(TimeValue.seconds(3))
                .measurementIterations(3).measurementTime(TimeValue.seconds(5))
                .forks(1)
                .build()).run();
    }
}

package io.github.wycst.wastnet.benchmarks.reflect;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark comparing Method.invoke() vs MethodHandle for route dispatch.
 * <p>
 * Simulates the exact production pattern:
 * {@code controller.method(request, response)}
 * <p>
 * Note on GC pressure (the real benefit):
 * <ul>
 *   <li>{@code Method.invoke(controller, req, res)}  → allocates {@code Object[3]} every call</li>
 *   <li>{@code MethodHandle.invokeExact(req, res)}   → zero allocation</li>
 * </ul>
 * This benchmark measures call overhead only. The GC benefit is visible in
 * long-running wrk/high-throughput scenarios, not in a µs-scale microbenchmark.
 *
 * @author wangyc
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Fork(2)
public class InvokeBenchmark {

    // ==================== Simulation types ====================

    /** SAM interface equivalent to HttpRoute */
    interface Handler {
        void handle(String path, Req request, Res response) throws Throwable;
    }

    static class Req { /* placeholder */ }
    static class Res { /* placeholder */ }

    static class UserController {
        public void handle(Req request, Res response) {
            // no-op, measure only invocation overhead
        }
    }

    // ==================== State ====================

    private static final String PATH = "/";

    private UserController controller;
    private Req request;
    private Res response;

    // 1. Direct interface call (baseline)
    private Handler directHandler;

    // 2. Reflection: Method.invoke()
    private Method reflectMethod;

    // 3. MethodHandle with insertArguments (production pattern)
    private MethodHandle boundMh;

    // 4. MethodHandle.invokeExact (no adapters, same sig)
    private MethodHandle rawMh;

    @Setup
    public void setup() throws Exception {
        controller = new UserController();
        request = new Req();
        response = new Res();

        // 1. Direct call — baseline
        directHandler = new Handler() {
            @Override
            public void handle(String path, Req req, Res res) {
                controller.handle(req, res);
            }
        };

        // 2. Method.invoke()
        reflectMethod = UserController.class.getMethod("handle", Req.class, Res.class);

        // 3. MethodHandle with bound controller (matches production code exactly)
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        MethodHandle raw = lookup.unreflect(reflectMethod);
        boundMh = MethodHandles.insertArguments(raw, 0, controller);

        // 4. MethodHandle.invokeExact — fully inlined, no adapters
        //    Pass the controller as a literal argument (not bound),
        //    so there is zero adapter overhead.
        rawMh = raw;
    }

    // ==================== Benchmarks ====================

    /** Baseline: direct interface call */
//    @Benchmark
    public void direct(Blackhole bh) throws Throwable {
        directHandler.handle(PATH, request, response);
        bh.consume(controller);
    }

    /** Production code BEFORE: Method.invoke() with Object[] allocation */
    @Benchmark
    public void reflection(Blackhole bh) throws Throwable {
        reflectMethod.invoke(controller, request, response);
        bh.consume(controller);
    }

    /** Production code AFTER: MethodHandle with bound controller + invoke */
    @Benchmark
    public void methodHandle(Blackhole bh) throws Throwable {
        boundMh.invoke(request, response);
        bh.consume(controller);
    }

    /** MethodHandle + invokeExact (zero adapter, zero allocation) */
    @Benchmark
    public void methodHandleExact(Blackhole bh) throws Throwable {
        rawMh.invokeExact((UserController) controller, (Req) request, (Res) response);
        bh.consume(controller);
    }

    // ==================== Runner ====================

    public static void main(String[] args) throws Exception {
        new Runner(new OptionsBuilder()
                .include(InvokeBenchmark.class.getSimpleName())
                .warmupIterations(3).warmupTime(TimeValue.seconds(2))
                .measurementIterations(3).measurementTime(TimeValue.seconds(5))
                .forks(1)
                .build()).run();
    }
}

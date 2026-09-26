/*
 * Copyright 2026, wangyunchao.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.wycst.wastnet.benchmarks.maintest;

import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.http.annotation.AnnotationRouterHandler;
import io.github.wycst.wastnet.http.annotation.ConverterConfig;
import io.github.wycst.wastnet.http.annotation.HttpMessageConverter;
import io.github.wycst.wastnet.http.handler.HttpRouterHandler;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * Benchmark: measure the startup-time cost of a full classpath scan + route
 * registration driven through {@link AnnotationRouterHandler#prepare()} (the
 * same path the server uses on start).
 *
 * <p>To repeat the scan without constructing a new handler each time, every
 * {@code prepare()} is followed by {@link AnnotationRouterHandler#clear()}
 * (which resets the {@code prepared} flag) and then another {@code prepare()},
 * so each iteration re-runs the whole scan from scratch.
 *
 * <p>The dev hot-reload watcher is disabled so the measured time reflects the
 * pure scan + registration cost, not filesystem watching.
 *
 * <p>Runs a single cold scan (no warm-up, one iteration) to reflect the real
 * startup cost: every class under the scanned packages is loaded into Metaspace
 * for the first time. Packages are hardcoded to {@code com,io,org}.
 */
public class AnnotationScanBenchmark {

    // Minimal no-op converter so @ResponseBody controllers register without a real serializer.
    private static final HttpMessageConverter NOOP_CONVERTER = new HttpMessageConverter() {
        @Override
        public Object read(HttpRequest request, ConverterConfig config, java.lang.reflect.Type type) {
            return null;
        }

        @Override
        public void write(Object value, ConverterConfig config, HttpResponse response) {
        }
    };

    public static void main(String[] args) throws Exception {
        String[] pkgs = {"com", "io", "org"};

        System.out.println("Scan packages : " + String.join(", ", pkgs));
        System.out.println("Mode          : single cold run (first scan = startup cost, no warm-up)");
        System.out.println("Hot reload    : disabled (isolates scan cost)");

        // One handler; configure packages + disable watcher once.
        AnnotationRouterHandler handler = new AnnotationRouterHandler();
        handler.hotReload(false);
        handler.messageConverter(NOOP_CONVERTER);
        handler.scanPackages(pkgs).property("msg", "hello world");

        // First (and only) scan: reflects the real startup cost. Every class under the
        // scanned packages is loaded into Metaspace for the first time here.
        long start = System.nanoTime();
        int failures = 0;
        int lastRoutes = -1;
        try {
            handler.prepare();
            lastRoutes = countRoutes(handler);
        } catch (Throwable t) {
            ++failures;
            System.err.println("Scan failure: " + t);
            t.printStackTrace();
        }
        long end = System.nanoTime();

        long totalNanos = end - start;
        double totalMillis = totalNanos / 1_000_000.0;

        System.out.printf("Routes        : %d%n", lastRoutes);
        System.out.printf("Failures      : %d%n", failures);
        System.out.printf("First scan    : %.3f ms (%d ns)%n", totalMillis, totalNanos);
    }

    // Read the protected exactRoutes map size via reflection for reporting.
    private static int countRoutes(AnnotationRouterHandler handler) {
        try {
            Field f = HttpRouterHandler.class.getDeclaredField("exactRoutes");
            f.setAccessible(true);
            Map<?, ?> m = (Map<?, ?>) f.get(handler);
            return m == null ? 0 : m.size();
        } catch (Exception e) {
            return -1;
        }
    }
}

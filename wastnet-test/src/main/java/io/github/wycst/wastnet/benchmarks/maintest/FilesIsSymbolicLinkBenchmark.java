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

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Benchmark: measure the time cost of calling Files.isSymbolicLink() 1,000,000 times.
 *
 * The File object is created the same way as HttpResourceRoute.handle():
 *     file = new File(docBase, rp)
 */
public class FilesIsSymbolicLinkBenchmark {

    private static final long ITERATIONS = 1_000_000L;

    public static void main(String[] args) throws IOException {
        // Mirror HttpResourceRoute: File file = new File(docBase, rp)
        String docBase = System.getProperty("user.dir");
        String rp = "."; // resolved relative path, exists
        File file = new File(docBase, rp);

        if (!file.exists()) {
            System.err.println("Resolved file does not exist: " + file.getAbsolutePath());
            return;
        }

        final Path path = file.toPath();

        System.out.println("Target file: " + file.getAbsolutePath());
        System.out.println("Warmup...");
        for (int i = 0; i < 100_000; i++) {
            Files.isSymbolicLink(file.toPath());
        }

        System.out.println("Running " + ITERATIONS + " iterations of Files.isSymbolicLink()...");
        long start = System.nanoTime();
        boolean last = false;
        for (long i = 0; i < ITERATIONS; i++) {
            last = Files.isSymbolicLink(FileSystems.getDefault().getPath(docBase + File.pathSeparator + rp));
        }
        long end = System.nanoTime();

        long totalNanos = end - start;
        double totalMillis = totalNanos / 1_000_000.0;
        double perCallNanos = (double) totalNanos / ITERATIONS;

        System.out.println("Last result: " + last);
        System.out.printf("Total time : %.3f ms (%d ns)%n", totalMillis, totalNanos);
        System.out.printf("Per call   : %.4f ns%n", perCallNanos);
    }
}

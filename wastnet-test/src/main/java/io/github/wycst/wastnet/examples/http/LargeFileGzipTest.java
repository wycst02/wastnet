package io.github.wycst.wastnet.examples.http;

import io.github.wycst.wast.log.Log;
import io.github.wycst.wast.log.LogFactory;
import io.github.wycst.wastnet.http.HTTPServer;
import io.github.wycst.wastnet.http.HttpConf;
import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.http.handler.HttpRequestHandler;
import io.github.wycst.wastnet.socket.tcp.NioConfig;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Random;

/**
 * Large file GZIP compression test
 * Tests streaming GZIP compression for files exceeding BODY_MEMORY_THRESHOLD
 */
public class LargeFileGzipTest {

    private static final Log log = LogFactory.getLog(LargeFileGzipTest.class);

    public static void main(String[] args) throws Exception {
        // Prepare test files
        prepareTestFiles();

        // Start server
        startTestServer();
    }

    private static void prepareTestFiles() throws IOException {
        File testDir = new File("test-files").getAbsoluteFile();
        if (!testDir.exists()) {
            testDir.mkdir();
        }

        // Create a large text file (> BODY_MEMORY_THRESHOLD = 512KB)
        // Using 1MB file to test streaming GZIP compression
        File largeFile = new File(testDir, "large.txt");
        if (!largeFile.exists() || largeFile.length() < HttpConf.BODY_MEMORY_THRESHOLD) {
            createLargeTextFile(largeFile, 1024 * 1024); // 1MB
            log.info("Created large file: {} ({} bytes)", largeFile.getName(), largeFile.length());
        } else {
            log.info("Large file already exists: {} ({} bytes)", largeFile.getName(), largeFile.length());
        }

        // Create a small text file for comparison (< BODY_MEMORY_THRESHOLD)
        File smallFile = new File(testDir, "small.txt");
        if (!smallFile.exists()) {
            createLargeTextFile(smallFile, 1024); // 1KB
            log.info("Created small file: {} ({} bytes)", smallFile.getName(), smallFile.length());
        } else {
            log.info("Small file already exists: {} ({} bytes)", smallFile.getName(), smallFile.length());
        }

        // Create a large JSON file
        File largeJson = new File(testDir, "large.json");
        if (!largeJson.exists() || largeJson.length() < HttpConf.BODY_MEMORY_THRESHOLD) {
            createLargeJsonFile(largeJson, 1024 * 1024); // 1MB
            log.info("Created large JSON: {} ({} bytes)", largeJson.getName(), largeJson.length());
        } else {
            log.info("Large JSON already exists: {} ({} bytes)", largeJson.getName(), largeJson.length());
        }

        log.info("BODY_MEMORY_THRESHOLD: {} bytes", HttpConf.BODY_MEMORY_THRESHOLD);
    }

    private static void createLargeTextFile(File file, int size) throws IOException {
        Random random = new Random();
        PrintWriter writer = new PrintWriter(new FileWriter(file));
        try {
            // Write repetitive but compressible content
            StringBuilder sb = new StringBuilder();
            while (file.length() < size) {
                sb.setLength(0);
                for (int i = 0; i < 100; ++i) {
                    sb.append("Line ").append(random.nextInt(10000))
                      .append(": This is a test line for GZIP compression testing. ")
                      .append("The content should be highly compressible. ");
                }
                sb.append('\n');
                writer.write(sb.toString());
            }
        } finally {
            writer.close();
        }
    }

    private static void createLargeJsonFile(File file, int size) throws IOException {
        Random random = new Random();
        PrintWriter writer = new PrintWriter(new FileWriter(file));
        try {
            writer.write("{\"items\":[\n");
            boolean first = true;
            while (file.length() < size) {
                if (!first) {
                    writer.write(",\n");
                }
                first = false;
                writer.write("  {\"id\":" + random.nextInt(100000)
                    + ",\"name\":\"Item" + random.nextInt(10000)
                    + "\",\"value\":" + random.nextDouble()
                    + ",\"description\":\"This is a test item description for compression testing\"}");
            }
            writer.write("\n]}");
        } finally {
            writer.close();
        }
    }

    private static void startTestServer() throws Exception {
        NioConfig nioConfig = new NioConfig();
        nioConfig.testMode();
        nioConfig.setWorkerNum(4);

        HTTPServer server = HTTPServer.of(8086, nioConfig)
                .requestHandler(new LargeFileHandler())
                .start();

        log.info("Large file GZIP test server started on port {}", server.getPort());
        log.info("Test URLs (use curl with --compressed or browser):");
        log.info("  Small file:   curl --compressed http://localhost:8086/files/small.txt");
        log.info("  Large file:   curl --compressed http://localhost:8086/files/large.txt");
        log.info("  Large JSON:   curl --compressed http://localhost:8086/files/large.json");
        log.info("");
        log.info("  Check headers: curl -I -H 'Accept-Encoding: gzip' http://localhost:8086/files/large.txt");
    }

    static class LargeFileHandler implements HttpRequestHandler {
        private final File testDir = new File("test-files").getAbsoluteFile();

        @Override
        public void handle(HttpRequest request, HttpResponse response) throws Throwable {
            String uri = request.getRequestUri();

            if (uri == null || !uri.startsWith("/files/")) {
                response.status(400).body("Invalid path".getBytes());
                return;
            }

            String filename = uri.substring("/files/".length());
            File file = new File(testDir, filename);

            log.info("Request: {} -> {} ({} bytes, gzip: {})",
                    uri, file.getName(), file.length(), response.isGzipSupported());

            response.sendFile(file);
        }
    }
}

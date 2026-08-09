package io.github.wycst.wastnet.examples.http;

import io.github.wycst.wast.log.Log;
import io.github.wycst.wast.log.LogFactory;
import io.github.wycst.wastnet.http.HTTPServer;
import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.http.HttpStatus;
import io.github.wycst.wastnet.http.handler.HttpRequestHandler;
import io.github.wycst.wastnet.socket.tcp.NioConfig;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/**
 * SendFile method usage examples and tests
 * Demonstrates various file transfer scenarios
 *
 * @author wangyc
 */
public class SendFileTest {

    private static final Log log = LogFactory.getLog(SendFileTest.class);

    public static void main(String[] args) throws Exception {

        // Disable default header
//        System.setProperty("wastnet.http.header.default.enabled", "false");

        // Prepare test files first
        prepareTestFiles();

        // Start server
        startTestServer();

        log.info("Server started successfully!");
        log.info("Press Ctrl+C to stop");
    }

    /**
     * Prepare test files with different types
     */
    private static void prepareTestFiles() throws IOException {
        File testDir = new File("test-files").getAbsoluteFile();
        if (!testDir.exists()) {
            testDir.mkdir();
            log.info("Created test-files directory at: {}", testDir.getAbsolutePath());
        }

        log.info("Test files directory absolute path: {}", testDir.getAbsolutePath());

        // HTML file
        File htmlFile = new File(testDir, "index.html");
        writeTestFile(htmlFile,
            "<!DOCTYPE html>\n" +
            "<html>\n" +
            "<head><title>Test Page</title></head>\n" +
            "<body><h1>SendFile Test</h1><p>This is a test HTML file.</p></body>\n" +
            "</html>"
        );
        log.info("Created file: {} (exists: {}, size: {} bytes)", htmlFile.getName(), htmlFile.exists(), htmlFile.length());

        // Plain text file
        File txtFile = new File(testDir, "example.txt");
        writeTestFile(txtFile,
            "This is a sample text file for sendFile() testing.\n" +
            "It contains multiple lines of text.\n" +
            "Line 3\n" +
            "Line 4\n"
        );
        log.info("Created file: {} (exists: {}, size: {} bytes)", txtFile.getName(), txtFile.exists(), txtFile.length());

        // CSS file
        File cssFile = new File(testDir, "style.css");
        writeTestFile(cssFile,
            "body { font-family: Arial, sans-serif; }\n" +
            "h1 { color: #333; }\n" +
            ".container { padding: 20px; }\n"
        );
        log.info("Created file: {} (exists: {}, size: {} bytes)", cssFile.getName(), cssFile.exists(), cssFile.length());

        // JavaScript file
        File jsFile = new File(testDir, "script.js");
        writeTestFile(jsFile,
            "function sayHello() {\n" +
            "    console.log('Hello from sendFile()!');\n" +
            "}\n" +
            "sayHello();\n"
        );
        log.info("Created file: {} (exists: {}, size: {} bytes)", jsFile.getName(), jsFile.exists(), jsFile.length());

        // JSON file
        File jsonFile = new File(testDir, "data.json");
        writeTestFile(jsonFile,
            "{\"status\":\"success\",\"data\":{\"id\":1,\"name\":\"Test\",\"value\":123.45}}"
        );
        log.info("Created file: {} (exists: {}, size: {} bytes)", jsonFile.getName(), jsonFile.exists(), jsonFile.length());

        // Empty file
        File emptyFile = new File(testDir, "empty.txt");
        writeTestFile(emptyFile, "");
        log.info("Created file: {} (exists: {}, size: {} bytes)", emptyFile.getName(), emptyFile.exists(), emptyFile.length());

        log.info("Test files prepared in test-files/ directory");
    }

    /**
     * Write content to test file
     */
    private static void writeTestFile(File file, String content) throws IOException {
        FileWriter writer = new FileWriter(file);
        try {
            writer.write(content);
        } finally {
            writer.close();
        }
    }

    /**
     * Start HTTP server with file transfer handlers
     */
    private static void startTestServer() throws Exception {
        NioConfig nioConfig = new NioConfig();
        nioConfig.testMode();
        nioConfig.setWorkerNum(4);

        HTTPServer httpServer = HTTPServer.of(8083, nioConfig)
                .requestHandler(new FileTransferHandler())
//                .printReadErrorLog(true)
//                .printApplicationMessage(true)
                .start();

        log.info("HTTP Server started on port " + httpServer.getPort());
        log.info("Test URLs:");
        log.info("  HTML file:    http://localhost:8083/files/index.html");
        log.info("  Text file:    http://localhost:8083/files/example.txt");
        log.info("  CSS file:     http://localhost:8083/files/style.css");
        log.info("  JavaScript:    http://localhost:8083/files/script.js");
        log.info("  JSON file:    http://localhost:8083/files/data.json");
        log.info("  Empty file:    http://localhost:8083/files/empty.txt");
        log.info("  Not found:     http://localhost:8083/files/notfound.txt");
    }

    /**
     * Request handler for file transfers
     */
        static class FileTransferHandler implements HttpRequestHandler {
        private final File testDir = new File("test-files").getAbsoluteFile();

        public void handle(HttpRequest request, HttpResponse response) throws Throwable {
            String uri = request.getRequestUri();

            // log.info("Received request URI: {}", uri);

            if (uri == null || !uri.startsWith("/files/")) {
                log.warn("Invalid path: {}", uri);
                sendError(response, HttpStatus.BAD_REQUEST, "Invalid path");
                return;
            }

            // Extract filename from URI
            String filename = uri.substring("/files/".length());
            File file = new File(testDir, filename);

            // log.info("Requested file: {}", file.getAbsolutePath());
            // log.info("File exists: {}, isFile: {}, canRead: {}", file.exists(), file.isFile(), file.canRead());

            // Send file using sendFile()
            // This method automatically:
            // - Sets Content-Length based on file size
            // - Sets Content-Type based on file extension
            // - Sends file content (zero-copy for non-SSL, buffered for SSL)
            // - Commits response and marks it as completed
            // - Returns 404 if file not found or cannot be read
            // log.info("Sending file: {} (size: {} bytes)", filename, file.length());
            response.sendFile(file);

            // log.info("sendFile() completed, response status: {}", response.getStatus());

            // Note: After sendFile(), response is completed and no further
            // modifications are allowed (all operations are silently ignored).
            // The following code would have no effect:
            // response.header("X-Extra", "ignored");  // Silently ignored
            // response.write("more data");            // Silently ignored
        }

        /**
         * Send error response
         */
        private void sendError(HttpResponse response, HttpStatus status, String message) {
            String html = "<html><body>" +
                    "<h1>" + status.code + " " + status.text + "</h1>" +
                    "<p>" + message + "</p>" +
                    "</body></html>";

            response.status(status)
                    .contentType("text/html;charset=utf-8")
                    .body(html.getBytes());
        }
    }
}

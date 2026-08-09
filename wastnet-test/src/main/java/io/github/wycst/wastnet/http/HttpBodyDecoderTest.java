package io.github.wycst.wastnet.http;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Comprehensive test class for HttpBodyDecoder
 * Tests various content types including multipart/form-data,
 * application/x-www-form-urlencoded, application/json, etc.
 *
 * @author test
 * @since 2024
 */
public class HttpBodyDecoderTest {

    public static void main(String[] args) {
        System.out.println("=== HttpBodyDecoder Test Suite ===\n");

        int passed = 0;
        int failed = 0;

        // Run all tests
        passed += testMultipartFormData() ? 1 : 0;
        failed += testMultipartFormData() ? 0 : 1;

        passed += testMultipartFileUpload() ? 1 : 0;
        failed += testMultipartFileUpload() ? 0 : 1;

        passed += testMultipartMixed() ? 1 : 0;
        failed += testMultipartMixed() ? 0 : 1;

        passed += testFormUrlencoded() ? 1 : 0;
        failed += testFormUrlencoded() ? 0 : 1;

        passed += testApplicationJson() ? 1 : 0;
        failed += testApplicationJson() ? 0 : 1;

        passed += testContentTypeDetection() ? 1 : 0;
        failed += testContentTypeDetection() ? 0 : 1;

        passed += testEmptyBody() ? 1 : 0;
        failed += testEmptyBody() ? 0 : 1;

        System.out.println("\n=== Test Summary ===");
        System.out.println("Total tests: " + (passed + failed));
        System.out.println("Passed: " + passed);
        System.out.println("Failed: " + failed);
        System.out.println("Success rate: " + String.format("%.2f", (passed * 100.0 / (passed + failed))) + "%");

        // Run performance test
        System.out.println("\n=== Performance Test ===");
        testPerformance();
    }

    /**
     * Test 1: Multipart form data with text fields only
     */
    private static boolean testMultipartFormData() {
        System.out.println("Test 1: Multipart form data with text fields");
        try {
            String boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW";
            String multipartData =
                    "------WebKitFormBoundary7MA4YWxkTrZu0gW\r\n" +
                    "Content-Disposition: form-data; name=\"username\"\r\n" +
                    "\r\n" +
                    "john.doe\r\n" +
                    "------WebKitFormBoundary7MA4YWxkTrZu0gW\r\n" +
                    "Content-Disposition: form-data; name=\"email\"\r\n" +
                    "\r\n" +
                    "john@example.com\r\n" +
                    "------WebKitFormBoundary7MA4YWxkTrZu0gW--";

            String contentType = "multipart/form-data; boundary=----WebKitFormBoundary7MA4YWxkTrZu0gW";
            byte[] bodyData = multipartData.getBytes(StandardCharsets.UTF_8);

            HttpBodyDecoder decoder = new HttpBodyDefaultDecoder(contentType, bodyData);

            String username = decoder.getMultipartFieldValue("username");
            String email = decoder.getMultipartFieldValue("email");

            if (username == null || email == null) {
                System.err.println("  ✗ Fields not found");
                return false;
            }

            if ("john.doe".equals(username) && "john@example.com".equals(email)) {
                System.out.println("  ✓ All fields decoded correctly");
                return true;
            } else {
                System.err.println("  ✗ Field values not correct");
                return false;
            }
        } catch (Exception e) {
            System.err.println("  ✗ Exception: " + e.getMessage());
            return false;
        }
    }

    /**
     * Test 2: Multipart with file upload
     */
    private static boolean testMultipartFileUpload() {
        System.out.println("Test 2: Multipart with file upload");
        try {
            String boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW";
            byte[] fileContent = "This is file content for testing".getBytes(StandardCharsets.UTF_8);

            StringBuilder multipartBuilder = new StringBuilder();
            multipartBuilder.append("------WebKitFormBoundary7MA4YWxkTrZu0gW\r\n");
            multipartBuilder.append("Content-Disposition: form-data; name=\"file\"; filename=\"test.txt\"\r\n");
            multipartBuilder.append("Content-Type: text/plain\r\n");
            multipartBuilder.append("\r\n");

            byte[] headerBytes = multipartBuilder.toString().getBytes(StandardCharsets.UTF_8);
            byte[] footerBytes = ("\r\n------WebKitFormBoundary7MA4YWxkTrZu0gW--").getBytes(StandardCharsets.UTF_8);

            byte[] bodyData = new byte[headerBytes.length + fileContent.length + footerBytes.length];
            System.arraycopy(headerBytes, 0, bodyData, 0, headerBytes.length);
            System.arraycopy(fileContent, 0, bodyData, headerBytes.length, fileContent.length);
            System.arraycopy(footerBytes, 0, bodyData, headerBytes.length + fileContent.length, footerBytes.length);

            String contentType = "multipart/form-data; boundary=----WebKitFormBoundary7MA4YWxkTrZu0gW";
            HttpBodyDecoder decoder = new HttpBodyDefaultDecoder(contentType, bodyData);

            MultipartField field = decoder.getMultipartField("file");
            if (field == null) {
                System.err.println("  ✗ Field 'file' not found");
                return false;
            }

            if ("file".equals(field.getName()) && field.isFile()) {
                if ("test.txt".equals(field.getFileName())) {
                    String fileData = field.getDataAsString();
                    if ("This is file content for testing".equals(fileData)) {
                        System.out.println("  ✓ File content matches");
                        return true;
                    }
                }
            }

            System.err.println("  ✗ File field not decoded correctly");
            return false;
        } catch (Exception e) {
            System.err.println("  ✗ Exception: " + e.getMessage());
            return false;
        }
    }

    /**
     * Test 3: Mixed multipart (fields and files)
     */
    private static boolean testMultipartMixed() {
        System.out.println("Test 3: Mixed multipart (fields and files)");
        try {
            String boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW";

            String multipartData =
                    "------WebKitFormBoundary7MA4YWxkTrZu0gW\r\n" +
                    "Content-Disposition: form-data; name=\"name\"\r\n" +
                    "\r\n" +
                    "Alice\r\n" +
                    "------WebKitFormBoundary7MA4YWxkTrZu0gW\r\n" +
                    "Content-Disposition: form-data; name=\"avatar\"; filename=\"avatar.jpg\"\r\n" +
                    "Content-Type: image/jpeg\r\n" +
                    "\r\n" +
                    "fake-jpeg-data\r\n" +
                    "------WebKitFormBoundary7MA4YWxkTrZu0gW--";

            String contentType = "multipart/form-data; boundary=----WebKitFormBoundary7MA4YWxkTrZu0gW";
            byte[] bodyData = multipartData.getBytes(StandardCharsets.UTF_8);

            HttpBodyDecoder decoder = new HttpBodyDefaultDecoder(contentType, bodyData);

            String nameValue = decoder.getMultipartFieldValue("name");
            MultipartField avatar = decoder.getMultipartField("avatar");

            if (nameValue == null || avatar == null) {
                System.err.println("  ✗ Fields not found");
                return false;
            }

            if (!"Alice".equals(nameValue)) {
                System.err.println("  ✗ Name field value incorrect");
                return false;
            }

            if (!avatar.isFile() || !"avatar.jpg".equals(avatar.getFileName())) {
                System.err.println("  ✗ Avatar file field not decoded correctly");
                return false;
            }

            System.out.println("  ✓ Mixed multipart decoded correctly (1 field, 1 file)");
            return true;
        } catch (Exception e) {
            System.err.println("  ✗ Exception: " + e.getMessage());
            return false;
        }
    }

    /**
     * Test 4: URL-encoded form data
     */
    private static boolean testFormUrlencoded() {
        System.out.println("Test 4: URL-encoded form data");
        try {
            String formData = "username=john.doe&email=john@example.com&age=30";
            String contentType = "application/x-www-form-urlencoded";
            byte[] bodyData = formData.getBytes(StandardCharsets.UTF_8);

            HttpBodyDecoder decoder = new HttpBodyDefaultDecoder(contentType, bodyData);

            String username = decoder.getUrlencodedParameter("username");
            String email = decoder.getUrlencodedParameter("email");
            String age = decoder.getUrlencodedParameter("age");

            if (!"john.doe".equals(username) ||
                !"john@example.com".equals(email) ||
                !"30".equals(age)) {
                System.err.println("  ✗ Parameter values incorrect");
                return false;
            }

            System.out.println("  ✓ URL-encoded form decoded correctly");
            return true;
        } catch (Exception e) {
            System.err.println("  ✗ Exception: " + e.getMessage());
            return false;
        }
    }

    /**
     * Test 5: Application JSON
     */
    private static boolean testApplicationJson() {
        System.out.println("Test 5: Application JSON");
        try {
            String jsonData = "{\"name\":\"John\",\"age\":30,\"email\":\"john@example.com\"}";
            String contentType = "application/json";
            byte[] bodyData = jsonData.getBytes(StandardCharsets.UTF_8);

            HttpBodyDecoder decoder = new HttpBodyDefaultDecoder(contentType, bodyData);

            if (decoder.isJson()) {
                System.out.println("  ✓ JSON content type detected correctly");
                return true;
            } else {
                System.err.println("  ✗ Not recognized as application/json");
                return false;
            }
        } catch (Exception e) {
            System.err.println("  ✗ Exception: " + e.getMessage());
            return false;
        }
    }

    /**
     * Test 6: Content type detection
     */
    private static boolean testContentTypeDetection() {
        System.out.println("Test 6: Content type detection");
        try {
            String testBody = "test data";

            // Test multipart
            HttpBodyDecoder decoder1 = new HttpBodyDefaultDecoder("multipart/form-data; boundary=test", testBody.getBytes());
            if (!decoder1.isMultipart()) {
                System.err.println("  ✗ Multipart not detected");
                return false;
            }

            // Test form-urlencoded
            HttpBodyDecoder decoder2 = new HttpBodyDefaultDecoder("application/x-www-form-urlencoded", testBody.getBytes());
            if (!decoder2.isFormUrlencoded()) {
                System.err.println("  ✗ Form urlencoded not detected");
                return false;
            }

            // Test JSON
            HttpBodyDecoder decoder3 = new HttpBodyDefaultDecoder("application/json", testBody.getBytes());
            if (!decoder3.isJson()) {
                System.err.println("  ✗ JSON not detected");
                return false;
            }

            HttpBodyDecoder decoder4 = new HttpBodyDefaultDecoder("text/json", testBody.getBytes());
            if (!decoder4.isJson()) {
                System.err.println("  ✗ text/json not detected");
                return false;
            }

            // Test octet-stream
            HttpBodyDecoder decoder5 = new HttpBodyDefaultDecoder("application/octet-stream", testBody.getBytes());
            if (!decoder5.isOctetStream()) {
                System.err.println("  ✗ Octet-stream not detected");
                return false;
            }

            System.out.println("  ✓ All content types detected correctly");
            return true;
        } catch (Exception e) {
            System.err.println("  ✗ Exception: " + e.getMessage());
            return false;
        }
    }

    /**
     * Test 7: Empty body
     */
    private static boolean testEmptyBody() {
        System.out.println("Test 7: Empty body");
        try {
            String contentType = "application/x-www-form-urlencoded";
            byte[] bodyData = new byte[0];

            HttpBodyDecoder decoder = new HttpBodyDefaultDecoder(contentType, bodyData);

            if (decoder.getUrlencodedParameterNames().isEmpty()) {
                System.out.println("  ✓ Empty body handled correctly");
                return true;
            } else {
                System.err.println("  ✗ Empty body should return empty names");
                return false;
            }
        } catch (Exception e) {
            System.err.println("  ✗ Exception: " + e.getMessage());
            return false;
        }
    }

    /**
     * Performance test with complex multipart data
     * Tests decoding performance 1,000,000 times
     */
    private static void testPerformance() {
        System.out.println("Creating complex multipart test data...");

        String boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW";

        // Build complex multipart data with multiple fields and files
        StringBuilder multipartBuilder = new StringBuilder();

        // Text fields
        multipartBuilder.append("------WebKitFormBoundary7MA4YWxkTrZu0gW\r\n");
        multipartBuilder.append("Content-Disposition: form-data; name=\"username\"\r\n");
        multipartBuilder.append("\r\n");
        multipartBuilder.append("john.doe\r\n");

        multipartBuilder.append("------WebKitFormBoundary7MA4YWxkTrZu0gW\r\n");
        multipartBuilder.append("Content-Disposition: form-data; name=\"email\"\r\n");
        multipartBuilder.append("\r\n");
        multipartBuilder.append("john.doe@example.com\r\n");

        multipartBuilder.append("------WebKitFormBoundary7MA4YWxkTrZu0gW\r\n");
        multipartBuilder.append("Content-Disposition: form-data; name=\"age\"\r\n");
        multipartBuilder.append("\r\n");
        multipartBuilder.append("30\r\n");

        multipartBuilder.append("------WebKitFormBoundary7MA4YWxkTrZu0gW\r\n");
        multipartBuilder.append("Content-Disposition: form-data; name=\"description\"\r\n");
        multipartBuilder.append("\r\n");
        multipartBuilder.append("This is a detailed description with special characters: áéíóúñ 你好世界\r\n");

        // File 1: Profile picture (simulated image data)
        byte[] file1Content = new byte[512]; // 512 bytes
        for (int i = 0; i < 512; ++i) {
            file1Content[i] = (byte) (i % 256);
        }
        multipartBuilder.append("------WebKitFormBoundary7MA4YWxkTrZu0gW\r\n");
        multipartBuilder.append("Content-Disposition: form-data; name=\"profile\"; filename=\"profile.jpg\"\r\n");
        multipartBuilder.append("Content-Type: image/jpeg\r\n");
        multipartBuilder.append("\r\n");

        // File 2: Document (simulated text data)
        byte[] file2Content = "This is a document content for performance testing. ".getBytes(StandardCharsets.UTF_8);
        // Repeat to make it ~2KB
        byte[] largeFile2 = new byte[2048];
        for (int i = 0; i < 2048; ++i) {
            largeFile2[i] = file2Content[i % file2Content.length];
        }

        // File 3: Binary data (simulated)
        byte[] file3Content = new byte[1024];
        for (int i = 0; i < 1024; ++i) {
            file3Content[i] = (byte) (Math.random() * 256);
        }

        // Construct final byte array
        byte[] header1Bytes = multipartBuilder.toString().getBytes(StandardCharsets.UTF_8);
        byte[] header2Bytes = ("\r\n------WebKitFormBoundary7MA4YWxkTrZu0gW\r\n" +
                "Content-Disposition: form-data; name=\"document\"; filename=\"resume.pdf\"\r\n" +
                "Content-Type: application/pdf\r\n" +
                "\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] header3Bytes = ("\r\n------WebKitFormBoundary7MA4YWxkTrZu0gW\r\n" +
                "Content-Disposition: form-data; name=\"binary\"; filename=\"data.bin\"\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] footerBytes = ("\r\n------WebKitFormBoundary7MA4YWxkTrZu0gW--").getBytes(StandardCharsets.UTF_8);

        int totalLength = header1Bytes.length + file1Content.length +
                         header2Bytes.length + largeFile2.length +
                         header3Bytes.length + file3Content.length +
                         footerBytes.length;

        byte[] bodyData = new byte[totalLength];
        int offset = 0;
        System.arraycopy(header1Bytes, 0, bodyData, offset, header1Bytes.length);
        offset += header1Bytes.length;
        System.arraycopy(file1Content, 0, bodyData, offset, file1Content.length);
        offset += file1Content.length;
        System.arraycopy(header2Bytes, 0, bodyData, offset, header2Bytes.length);
        offset += header2Bytes.length;
        System.arraycopy(largeFile2, 0, bodyData, offset, largeFile2.length);
        offset += largeFile2.length;
        System.arraycopy(header3Bytes, 0, bodyData, offset, header3Bytes.length);
        offset += header3Bytes.length;
        System.arraycopy(file3Content, 0, bodyData, offset, file3Content.length);
        offset += file3Content.length;
        System.arraycopy(footerBytes, 0, bodyData, offset, footerBytes.length);

        String contentType = "multipart/form-data; boundary=----WebKitFormBoundary7MA4YWxkTrZu0gW";

        System.out.println("Test data size: " + bodyData.length + " bytes");
        System.out.println("Starting performance test (1,000,000 iterations)...\n");

        // print test data
        System.out.println("test size:  " + bodyData.length + "\r\n" + new String(bodyData, StandardCharsets.UTF_8));

        // Warm-up
        System.out.println("Warming up...");

        int warmupCount = 1000;
        for (int i = 0; i < warmupCount; ++i) {
            HttpBodyDecoder decoder = new HttpBodyDefaultDecoder(contentType, bodyData);
            // Trigger decoding by accessing fields
            decoder.getMultipartFieldNames();
        }
        System.out.println("Warm-up complete.\n");

        // Actual performance test
        final int iterations = 1_000_000;
        long startTime = System.currentTimeMillis();

        int totalFieldsDecoded = 0;
        int totalFilesDecoded = 0;

        for (int i = 0; i < iterations; ++i) {
            HttpBodyDecoder decoder = new HttpBodyDefaultDecoder(contentType, bodyData);
            java.util.Set<String> fieldNames = decoder.getMultipartFieldNames();
            for (String name : fieldNames) {
                List<MultipartField> fields = decoder.getMultipartFields(name);
                if (fields != null) {
                    totalFieldsDecoded += fields.size();
                    for (MultipartField field : fields) {
                        if (field.isFile()) {
                            totalFilesDecoded++;
                        }
                    }
                }
            }

            // Progress reporting
            if ((i + 1) % 100000 == 0) {
                long elapsed = System.currentTimeMillis() - startTime;
                double progress = (i + 1) * 100.0 / iterations;
                System.out.printf("Progress: %6.2f%% (%7d/%7d iterations, %5dms elapsed)%n",
                        progress, i + 1, iterations, elapsed);
            }
        }

        long endTime = System.currentTimeMillis();
        long totalTime = endTime - startTime;

        // Calculate statistics
        double avgTimePerIteration = (double) totalTime / iterations;
        double tps = (iterations * 1000.0) / totalTime; // Transactions per second
        double avgDataRate = ((long) bodyData.length * iterations) / (totalTime / 1000.0) / (1024 * 1024); // MB/s

        System.out.println("\n=== Performance Results ===");
        System.out.println("Total iterations: " + iterations);
        // 2026-04-05 use 6.753s
        System.out.println("Total time: " + totalTime + " ms (" + (totalTime / 1000.0) + " seconds)");
        System.out.println("Average time per iteration: " + String.format("%.4f", avgTimePerIteration) + " ms");
        System.out.println("Throughput: " + String.format("%.2f", tps) + " iterations/second");
        System.out.println("Data processing rate: " + String.format("%.2f", avgDataRate) + " MB/s");
        System.out.println("Total fields decoded: " + totalFieldsDecoded);
        System.out.println("Total files decoded: " + totalFilesDecoded);
        System.out.println("\nTest completed successfully!");
    }
}

package io.github.wycst.wastnet.http;

import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Map;

import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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

    //


    // ==================== HttpBodyDefaultDecoder ====================

    @Test
    public void testBodyDefaultDecoderMultipartSimple() {
        // Actual multipart data
        String boundary = "--boundary123";
        byte[] body = (
                boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"field1\"\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n" +
                "value1\r\n" +
                boundary + "--\r\n"
        ).getBytes();

        HttpBodyDefaultDecoder decoder = new HttpBodyDefaultDecoder(
                "multipart/form-data; boundary=boundary123", body);
        assertEquals("value1", decoder.getParameter("field1"));
    }

    @Test
    public void testBodyDefaultDecoderMultipartMultiple() {
        String boundary = "--testbound";
        byte[] body = (
                boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"a\"\r\n" +
                "\r\n" +
                "val_a\r\n" +
                boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"b\"\r\n" +
                "\r\n" +
                "val_b\r\n" +
                boundary + "--\r\n"
        ).getBytes();

        HttpBodyDefaultDecoder decoder = new HttpBodyDefaultDecoder(
                "multipart/form-data; boundary=testbound", body);
        assertEquals("val_a", decoder.getParameter("a"));
        assertEquals("val_b", decoder.getParameter("b"));
    }

    @Test
    public void testBodyDefaultDecoderMultipartNoBoundaryPrefix() {
        // Body that doesn't start with boundary - should return empty
        byte[] body = "not a multipart body".getBytes();
        HttpBodyDefaultDecoder decoder = new HttpBodyDefaultDecoder(
                "multipart/form-data; boundary=boundary123", body);
        assertNull(decoder.getParameter("field1"));
    }

    @Test
    public void testBodyDefaultDecoderMultipartNoFieldName() {
        String boundary = "--boundary";
        byte[] body = (
                boundary + "\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n" +
                "content\r\n" +
                boundary + "--\r\n"
        ).getBytes();

        // No Content-Disposition means no field name
        HttpBodyDefaultDecoder decoder = new HttpBodyDefaultDecoder(
                "multipart/form-data; boundary=boundary", body);
        assertNull(decoder.getParameter("field1"));
    }

    @Test
    public void testBodyDefaultDecoderMultipartWithFilename() {
        String boundary = "--bound";
        byte[] body = (
                boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"test.txt\"\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n" +
                "file content here\r\n" +
                boundary + "--\r\n"
        ).getBytes();

        HttpBodyDefaultDecoder decoder = new HttpBodyDefaultDecoder(
                "multipart/form-data; boundary=bound", body);
        // With filename, getMultipartFields returns non-null list
        java.util.List<MultipartField> fields = decoder.getMultipartFields("file");
        assertNotNull(fields, "Should have file field");
        assertFalse(fields.isEmpty(), "File field list should not be empty");
    }

    @Test
    public void testBodyDefaultDecoderFormUrlencodedComplex() {
        String body = "name=hello+world&tags=a%2Cb&empty=&num=42";
        HttpBodyDefaultDecoder decoder = new HttpBodyDefaultDecoder(
                "application/x-www-form-urlencoded", body.getBytes());
        assertEquals("hello world", decoder.getParameter("name"));
        assertNotNull(decoder.getParameter("empty"));
        assertEquals("42", decoder.getParameter("num"));
    }

    // ==================== HttpBodyStreamDecoder ====================

    @Test
    public void testBodyStreamDecoderBoundaryCheckFail() throws Exception {
        // Data too short (limit < boundary.length)
        InputStream in = new ByteArrayInputStream("short".getBytes());
        HttpBodyStreamDecoder decoder = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=----BoundaryLong", in);
        assertNull(decoder.getParameter("x"));
    }

    @Test
    public void testBodyStreamDecoderEndMarker() throws Exception {
        // Properly formatted multipart with end marker --boundary--
        String boundary = "----BOUND";
        byte[] body = (
                "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"test\"\r\n" +
                "\r\n" +
                "hello\r\n" +
                "--" + boundary + "--\r\n"
        ).getBytes();

        InputStream in = new ByteArrayInputStream(body);
        HttpBodyStreamDecoder decoder = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=" + boundary, in);
        assertEquals("hello", decoder.getParameter("test"));
    }

    @Test
    public void testBodyStreamDecoderFormUrlencodedNonChunked() throws Exception {
        // Non-chunked stream (content-length > MAX_BODY_IN_MEMORY) throws
        InputStream in = new ByteArrayInputStream("key=val".getBytes());
        HttpBodyStreamDecoder decoder = new HttpBodyStreamDecoder(
                "application/x-www-form-urlencoded", in);
        assertThrows(IllegalStateException.class, decoder::decodeFormUrlencoded);
    }

    @Test
    public void testBodyStreamDecoderNullStream() throws Exception {
        HttpBodyStreamDecoder decoder = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=test", (InputStream) null);
        assertNull(decoder.getParameter("x"));
    }

    @Test
    public void testBodyStreamDecoderNotMultipart() throws Exception {
        HttpBodyStreamDecoder decoder = new HttpBodyStreamDecoder(
                "application/json", new ByteArrayInputStream("{}".getBytes()));
        decoder.doDecodeMultipartFields("--boundary".getBytes());
    }

    // ==================== HttpBodyStreamDecoder: form-urlencoded with chunked stream ====================

    @Test
    public void testBodyStreamDecoderFormUrlencodedChunked() throws Exception {
        ChannelContext mockCtx = mock(ChannelContext.class);
        when(mockCtx.isChannelClosed()).thenReturn(false);
        when(mockCtx.readFully(any(byte[].class), anyInt(), anyInt(), anyLong()))
                .thenReturn(-1);
        byte[] wire = "7\r\nkey=val\r\n0\r\n\r\n".getBytes();
        HttpChunkedStream chunkedStream = new HttpChunkedStream(wire, mockCtx);
        HttpBodyStreamDecoder decoder = new HttpBodyStreamDecoder(
                "application/x-www-form-urlencoded", chunkedStream);
        assertEquals("val", decoder.getParameter("key"));
    }

    // ==================== HttpBodyStreamDecoder: readFieldToFile skipContent paths ====================

    @Test
    public void testBodyStreamDecoderReadFieldToFileSkipContentNullField() throws Exception {
        // Create data where boundary is found in buffer (first boundary triggers readFieldToFile)
        // But with a field that has no Content-Disposition name → fieldName is null → skipContent=true
        // We use a data pattern where the boundary delimiter is NOT found within the buffer range
        // To trigger readFieldToFile, need boundary not in buffer: use very short initial data and boundary at programmatic pos
        String boundary = "----BOUND";
        // Make data where the first boundary + headers consume most of the stream
        StringBuilder sb = new StringBuilder();
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Disposition: form-data; name=\"\"\r\n"); // empty name
        sb.append("Content-Type: text/plain\r\n\r\n");
        sb.append("datadata\r\n");
        sb.append("--").append(boundary).append("--\r\n");
        byte[] body = sb.toString().getBytes();
        InputStream in = new ByteArrayInputStream(body);
        HttpBodyStreamDecoder decoder = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=" + boundary, in);
        // Without a field name, the field won't show up in params
        assertNull(decoder.getParameter("x"));
    }

    @Test
    public void testBodyStreamDecoderNoFieldName() throws Exception {
        // Multipart without Content-Disposition name
        String boundary = "----BOUND";
        byte[] body = (
                "--" + boundary + "\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n" +
                "data\r\n" +
                "--" + boundary + "--\r\n"
        ).getBytes();

        InputStream in = new ByteArrayInputStream(body);
        HttpBodyStreamDecoder decoder = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=" + boundary, in);
        assertNull(decoder.getParameter("x"));
    }

    @Test
    public void testBodyStreamDecoderHeaderNotFound() throws Exception {
        // headers without CRLFCRLF within buffer
        String boundary = "----BOUNDARY";
        // Make header data that fills buffer without CRLFCRLF
        byte[] headerPart = new byte[4096];
        Arrays.fill(headerPart, (byte) 'A');
        byte[] bodyBytes = ("--" + boundary + "\r\n" + new String(headerPart)).getBytes();

        InputStream in = new ByteArrayInputStream(bodyBytes);
        HttpBodyStreamDecoder decoder = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=" + boundary, in);
        assertNull(decoder.getParameter("x"));
    }

    // ==================== HttpBodyDecoder base class ====================

    @Test
    public void testBodyDecoderContentTypeParsing() {
        HttpBodyDefaultDecoder decoder = new HttpBodyDefaultDecoder(
                "multipart/form-data; boundary=bound123", "x".getBytes());
        assertTrue(decoder.isMultipart());
        assertFalse(decoder.isFormUrlencoded());

        HttpBodyDefaultDecoder formDecoder = new HttpBodyDefaultDecoder(
                "application/x-www-form-urlencoded", "a=1".getBytes());
        assertTrue(formDecoder.isFormUrlencoded());
        assertFalse(formDecoder.isMultipart());
    }

    // ==================== HttpBodyDecoder.of() ====================

    @Test
    public void testOfWithStreamRequest() {
        HttpRequest mockReq = mock(HttpRequest.class);
        when(mockReq.getContentType()).thenReturn("application/json");
        when(mockReq.isStream()).thenReturn(true);
        when(mockReq.bodyStream()).thenReturn(new ByteArrayInputStream("{}".getBytes()));
        HttpBodyDecoder decoder = HttpBodyDecoder.of(mockReq, ChannelContext.EMPTY_CONTEXT);
        assertNotNull(decoder);
        assertTrue(decoder instanceof HttpBodyStreamDecoder);
    }

    // ==================== extractContentTypeParam: quoted/unclosed ====================

    @Test
    public void testQuotedCharset() {
        HttpBodyDefaultDecoder dec = new HttpBodyDefaultDecoder(
                "text/plain; charset=\"UTF-8\"", "data".getBytes());
        assertEquals("UTF-8", dec.getCharset().name());
    }

    @Test
    public void testUnclosedQuote() {
        // charset="UTF-8 (no closing quote) → end = content type length
        HttpBodyDefaultDecoder dec = new HttpBodyDefaultDecoder(
                "text/plain; charset=\"UTF-8", "data".getBytes());
        assertEquals("UTF-8", dec.getCharset().name());
    }

    @Test
    public void testSpaceDelimitedBoundary() {
        // Unquoted param, space delimiter
        HttpBodyDefaultDecoder dec = new HttpBodyDefaultDecoder(
                "multipart/form-data; boundary=abc def", "x".getBytes());
        assertNull(dec.getParameter("f"));
    }


    @Test
    public void testDecodeMultipartFieldsNotMultipart() {
        HttpBodyDefaultDecoder dec = new HttpBodyDefaultDecoder(
                "application/json", "{}".getBytes());
        dec.decodeMultipartFields(); // should set empty map, no exception
        assertTrue(dec.getMultipartFieldNames().isEmpty());
    }


    @Test
    public void testDecodeMultipartFieldsExceptionCaught() {
        // Stream that throws IOException → caught by catch block
        InputStream faulty = new InputStream() {
            @Override
            public int read() throws java.io.IOException {
                throw new java.io.IOException("simulated");
            }
            @Override
            public int read(byte[] b, int off, int len) throws java.io.IOException {
                throw new java.io.IOException("simulated");
            }
        };
        HttpBodyStreamDecoder dec = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=bound", faulty);
        dec.decodeMultipartFields();
        assertTrue(dec.getMultipartFieldNames().isEmpty());
    }


    @Test
    public void testGetMultipartFieldValuesWithFileField() {
        String boundary = "--bound";
        byte[] body = (boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"f\"; filename=\"x.txt\"\r\n"
                + "Content-Type: text/plain\r\n"
                + "\r\n"
                + "data\r\n"
                + boundary + "--\r\n").getBytes();
        HttpBodyDefaultDecoder dec = new HttpBodyDefaultDecoder(
                "multipart/form-data; boundary=bound", body);
        // File field is ignored in getMultipartFieldValues → returns empty list
        assertTrue(dec.getMultipartFieldValues("f").isEmpty());
    }


    @Test
    public void testGetParameterValuesMultipart() {
        String boundary = "--bound";
        byte[] body = (boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"x\"\r\n"
                + "\r\n"
                + "y\r\n"
                + boundary + "--\r\n").getBytes();
        HttpBodyDefaultDecoder dec = new HttpBodyDefaultDecoder(
                "multipart/form-data; boundary=bound", body);
        assertEquals("y", dec.getParameterValues("x").get(0));
    }


    @Test
    public void testExtractBoundaryEmpty() {
        HttpBodyDefaultDecoder dec = new HttpBodyDefaultDecoder(
                "multipart/form-data; boundary=", "data".getBytes());
        assertNull(dec.getParameter("f"));
    }


    @Test
    public void testStartsWithBoundaryDataTooShort() {
        byte[] boundary = "--test".getBytes();
        byte[] data = "x".getBytes();
        assertFalse(HttpBodyDecoder.startsWithBoundary(data, boundary));
    }


    @Test
    public void testParseMultipartHeadersLfOnly() {
        // Headers with \n only (no \r) before Content-Type
        String data = "Content-Type: text/plain\n\n";
        HttpBuf[] headers = HttpBodyDecoder.parseMultipartHeaders(data.getBytes(), 0, data.length());
        assertNotNull(headers);
        assertEquals("text/plain", headers[2].toString());
    }


    @Test
    public void testExtractHeaderParamValueEmpty() {
        String fullData = "Content-Disposition: form-data; name=  ;\r\n\r\n";
        HttpBuf[] headers = HttpBodyDecoder.parseMultipartHeaders(fullData.getBytes(), 0, fullData.length());
        assertNotNull(headers);
    }

    // ==================== extractHeaderParamValue: escaped quote in quoted value ====================

    @Test
    public void testExtractHeaderParamValueEscapedQuote() {
        // name="file\"name" → value contains escaped quote, covers escape handling
        String fullData = "Content-Disposition: form-data; name=\"file\\\"name\"\r\n\r\n";
        HttpBuf[] headers = HttpBodyDecoder.parseMultipartHeaders(fullData.getBytes(), 0, fullData.length());
        assertNotNull(headers);
        assertEquals("file\"name", headers[0].toString());
    }

    // ==================== extractHeaderParamValue: unclosed quoted value ====================

    @Test
    public void testExtractHeaderParamValueUnclosedQuote() {
        // name="unclosed → no closing quote, covers i >= end branch
        String fullData = "Content-Disposition: form-data; name=\"unclosed\r\n\r\n";
        HttpBuf[] headers = HttpBodyDecoder.parseMultipartHeaders(fullData.getBytes(), 0, fullData.length());
        assertNotNull(headers);
        assertEquals("unclosed", headers[0].toString());
    }

    // ==================== extractHeaderParamValue: unquoted with trailing spaces ====================

    @Test
    public void testExtractHeaderParamValueUnquotedTrim() {
        // name=value   ; → trailing spaces trimmed, covers line 597
        String fullData = "Content-Disposition: form-data; name=value   ;\r\n\r\n";
        HttpBuf[] headers = HttpBodyDecoder.parseMultipartHeaders(fullData.getBytes(), 0, fullData.length());
        assertNotNull(headers);
        assertEquals("value", headers[0].toString());
    }


    @Test
    public void testFindNewlineGuaranteedLfOnly() {
        byte[] data = "hello\nworld".getBytes();
        int pos = HttpBodyDecoder.findNewlineGuaranteed(data, 0);
        assertEquals(5, pos); // position of \n (since no \r)
    }


    /** Non-multipart branch : returns empty list, no decode. */
    @Test
    public void testGetOrderedMultipartFieldsNotMultipart() {
        HttpBodyDefaultDecoder decoder = new HttpBodyDefaultDecoder("text/plain", "hello".getBytes());
        List<MultipartField> fields = decoder.getOrderedMultipartFields();
        assertNotNull(fields);
        assertTrue(fields.isEmpty());
    }

    /** Single multipart field: triggers decode , loop , and the size<=1 no-sort branch ( false). */
    @Test
    public void testGetOrderedMultipartFieldsSingleField() {
        String boundary = "--78a9b0c1d2e3f4";
        byte[] body = (
                boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"field1\"\r\n" +
                "\r\n" +
                "value1\r\n" +
                boundary + "--\r\n"
        ).getBytes();
        HttpBodyDefaultDecoder decoder = new HttpBodyDefaultDecoder("multipart/form-data; boundary=78a9b0c1d2e3f4", body);
        List<MultipartField> fields = decoder.getOrderedMultipartFields();
        assertEquals(1, fields.size());
        assertEquals("field1", fields.get(0).getName());
        assertEquals("value1", fields.get(0).getDataAsString());
    }

    /** Multiple multipart fields: triggers decode + loop + the size>1 sort branch ( true). */
    @Test
    public void testGetOrderedMultipartFieldsMultipleFieldsSorted() {
        String boundary = "--78a9b0c1d2e3f4";
        // second field listed first in body so the comparator actually reorders by index
        byte[] body = (
                boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"fieldB\"\r\n" +
                "\r\n" +
                "valB\r\n" +
                boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"fieldA\"\r\n" +
                "\r\n" +
                "valA\r\n" +
                boundary + "--\r\n"
        ).getBytes();
        HttpBodyDefaultDecoder decoder = new HttpBodyDefaultDecoder("multipart/form-data; boundary=78a9b0c1d2e3f4", body);
        List<MultipartField> fields = decoder.getOrderedMultipartFields();
        assertEquals(2, fields.size());
        // fields are ordered by index (occurrence order in body): fieldB first, then fieldA
        assertEquals("fieldB", fields.get(0).getName());
        assertEquals("fieldA", fields.get(1).getName());
        assertEquals("valB", fields.get(0).getDataAsString());
        assertEquals("valA", fields.get(1).getDataAsString());
    }

}

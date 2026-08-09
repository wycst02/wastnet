package io.github.wycst.wastnet.examples.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Random;

/**
 * HTTP 客户端测试：发送 2.1MB 大小的请求体
 *
 * @author wangyc
 */
public class HttpClientLargeBodyTest {

    private static final String SERVER_URL = "http://localhost:8083/";
    private static final int BODY_SIZE = (int) (2 * 1024); // 2KB per field
    private static final String BOUNDARY = "----WebKitFormBoundary7MA4YWxkTrZu0gW";

    public static void main(String[] args) throws Exception {
        System.out.println("开始发送 multipart 请求到 " + SERVER_URL);
        System.out.println("每个字段大小: " + BODY_SIZE + " bytes");

        long startTime = System.currentTimeMillis();
        String response = sendMultipartRequest(SERVER_URL);
        long endTime = System.currentTimeMillis();

        System.out.println("\n请求完成，耗时: " + (endTime - startTime) + "ms");
        System.out.println("响应: " + response);

        while (true) {
            startTime = System.currentTimeMillis();
            response = sendMultipartRequest(SERVER_URL);
            endTime = System.currentTimeMillis();
            System.out.println("\n请求完成，耗时: " + (endTime - startTime) + "ms");
            System.out.println("响应: " + response);
            Thread.sleep(10000);
        }
    }

    /**
     * 生成指定大小的随机数据
     */
    private static byte[] generateBodyData(int size) {
        byte[] data = new byte[size];
        Random random = new Random();
        random.nextBytes(data);
        return data;
    }

    /**
     * 发送 multipart/form-data 请求，包含两个上传域
     */
    private static String sendMultipartRequest(String urlStr) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setDoInput(true);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(30000000);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + BOUNDARY);

            System.out.println("正在发送 multipart 请求...");

            try (OutputStream out = conn.getOutputStream()) {
                byte[] fileData1 = generateBodyData(BODY_SIZE);
                byte[] fileData2 = generateBodyData(BODY_SIZE);
                byte[] fileData3 = generateBodyData(BODY_SIZE);
                byte[] fileData4 = generateBodyData(BODY_SIZE);

                // Field 1: file1
                StringBuilder header1 = new StringBuilder();
                header1.append("--").append(BOUNDARY).append("\r\n");
                header1.append("Content-Disposition: form-data; name=\"file1\"; filename=\"test1.bin\"\r\n");
                header1.append("Content-Type: application/octet-stream\r\n\r\n");
                out.write(header1.toString().getBytes("UTF-8"));
                out.write(fileData1);
                out.write("\r\n".getBytes("UTF-8"));

                // Field 2: file2
                StringBuilder header2 = new StringBuilder();
                header2.append("--").append(BOUNDARY).append("\r\n");
                header2.append("Content-Disposition: form-data; name=\"file2\"; filename=\"test2.bin\"\r\n");
                header2.append("Content-Type: application/octet-stream\r\n\r\n");
                out.write(header2.toString().getBytes("UTF-8"));
                out.write(fileData2);
                out.write("\r\n".getBytes("UTF-8"));

                // Field 3: file3
                StringBuilder header3 = new StringBuilder();
                header3.append("--").append(BOUNDARY).append("\r\n");
                header3.append("Content-Disposition: form-data; name=\"file3\"; filename=\"test3.bin\"\r\n");
                header3.append("Content-Type: application/octet-stream\r\n\r\n");
                out.write(header3.toString().getBytes("UTF-8"));
                out.write(fileData3);
                out.write("\r\n".getBytes("UTF-8"));

                // Field 4: file4
                StringBuilder header4 = new StringBuilder();
                header4.append("--").append(BOUNDARY).append("\r\n");
                header4.append("Content-Disposition: form-data; name=\"file4\"; filename=\"test4.bin\"\r\n");
                header4.append("Content-Type: application/octet-stream\r\n\r\n");
                out.write(header4.toString().getBytes("UTF-8"));
                out.write(fileData4);
                out.write("\r\n".getBytes("UTF-8"));

                // End boundary
                out.write(("--" + BOUNDARY + "--\r\n").getBytes("UTF-8"));
                out.flush();
            }

            System.out.println("请求体发送完成，等待响应...");

            int responseCode = conn.getResponseCode();
            System.out.println("响应状态码: " + responseCode);

            try (InputStream in = conn.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {

                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }

                return out.toString("UTF-8");
            }
        } finally {
            // conn.disconnect();
        }
    }
}

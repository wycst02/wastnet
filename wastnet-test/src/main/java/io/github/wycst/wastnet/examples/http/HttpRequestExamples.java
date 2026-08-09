package io.github.wycst.wastnet.examples.http;

/**
 * 完整HTTP请求报文示例
 * 包含各种常见的HTTP请求格式，用于测试和参考
 *
 * @author wangyc
 */
public class HttpRequestExamples {

    /**
     * 示例1: 简单的GET请求
     * 获取首页
     */
    public static final String SIMPLE_GET_REQUEST = "" +
            "GET / HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36\r\n" +
            "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8\r\n" +
            "Accept-Language: zh-CN,zh;q=0.9,en;q=0.8\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n";

    /**
     * 示例2: 带查询参数的GET请求
     * 获取用户信息
     */
    public static final String GET_WITH_QUERY_PARAMS = "" +
            "GET /api/user?id=1001&name=john&age=25 HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "Accept: application/json\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n";

    /**
     * 示例3: POST请求 - JSON数据
     * 提交用户信息
     */
    public static final String POST_JSON_REQUEST = "" +
            "POST /api/user/create HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "Content-Type: application/json;charset=utf-8\r\n" +
            "Content-Length: 85\r\n" +
            "Accept: application/json\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n" +
            "{\"id\":1002,\"name\":\"Alice\",\"email\":\"alice@example.com\",\"age\":28,\"city\":\"Beijing\"}";

    /**
     * 示例4: POST请求 - Form表单数据
     * 用户登录
     */
    public static final String POST_FORM_REQUEST = "" +
            "POST /api/login HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "Content-Type: application/x-www-form-urlencoded\r\n" +
            "Content-Length: 35\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n" +
            "username=john&password=secret123";

    /**
     * 示例5: 文件上传请求 - multipart/form-data
     * 上传单个文件
     */
    public static final String FILE_UPLOAD_REQUEST = "" +
            "POST /upload HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "Content-Type: multipart/form-data; boundary=----WebKitFormBoundary7MA4YWxkTrZu0gW\r\n" +
            "Content-Length: 243\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n" +
            "------WebKitFormBoundary7MA4YWxkTrZu0gW\r\n" +
            "Content-Disposition: form-data; name=\"file\"; filename=\"test.txt\"\r\n" +
            "Content-Type: text/plain\r\n" +
            "\r\n" +
            "This is a test file content for upload.\r\n" +
            "------WebKitFormBoundary7MA4YWxkTrZu0gW--\r\n";

    /**
     * 示例6: 文件上传请求 - 多字段+文件
     * 上传文件并附加表单字段
     */
    public static final String FILE_UPLOAD_WITH_FIELDS = "" +
            "POST /upload HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "Content-Type: multipart/form-data; boundary=----WebKitFormBoundary7MA4YWxkTrZu0gW\r\n" +
            "Content-Length: 405\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n" +
            "------WebKitFormBoundary7MA4YWxkTrZu0gW\r\n" +
            "Content-Disposition: form-data; name=\"username\"\r\n" +
            "\r\n" +
            "john\r\n" +
            "------WebKitFormBoundary7MA4YWxkTrZu0gW\r\n" +
            "Content-Disposition: form-data; name=\"description\"\r\n" +
            "\r\n" +
            "This is a test upload\r\n" +
            "------WebKitFormBoundary7MA4YWxkTrZu0gW\r\n" +
            "Content-Disposition: form-data; name=\"file\"; filename=\"document.txt\"\r\n" +
            "Content-Type: text/plain\r\n" +
            "\r\n" +
            "Document content goes here.\r\n" +
            "------WebKitFormBoundary7MA4YWxkTrZu0gW--\r\n";

    /**
     * 示例7: PUT请求 - 更新资源
     * 更新用户信息
     */
    public static final String PUT_REQUEST = "" +
            "PUT /api/user/1001 HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "Content-Type: application/json;charset=utf-8\r\n" +
            "Content-Length: 58\r\n" +
            "Accept: application/json\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n" +
            "{\"name\":\"John Updated\",\"age\":26,\"city\":\"Shanghai\"}";

    /**
     * 示例8: DELETE请求 - 删除资源
     * 删除用户
     */
    public static final String DELETE_REQUEST = "" +
            "DELETE /api/user/1001 HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "Content-Type: application/json\r\n" +
            "Accept: application/json\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n";

    /**
     * 示例9: 带Cookie的请求
     * 带认证信息的请求
     */
    public static final String REQUEST_WITH_COOKIE = "" +
            "GET /api/profile HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "Cookie: sessionId=abc123def456; userId=1001; preferences=theme=dark\r\n" +
            "Accept: application/json\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n";

    /**
     * 示例10: 带Authorization的请求
     * Bearer Token认证
     */
    public static final String REQUEST_WITH_AUTH = "" +
            "GET /api/protected/resource HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ\r\n" +
            "Accept: application/json\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n";

    /**
     * 示例11: Range请求 - 断点续传/分片下载
     * 请求文件的指定范围
     */
    public static final String RANGE_REQUEST = "" +
            "GET /download/largefile.zip HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "Range: bytes=0-1023\r\n" +
            "Accept: */*\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n";

    /**
     * 示例12: 条件请求 - If-Modified-Since
     * 缓存验证，只获取修改过的资源
     */
    public static final String CONDITIONAL_REQUEST_IF_MODIFIED_SINCE = "" +
            "GET /api/data HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "If-Modified-Since: Wed, 21 Oct 2015 07:28:00 GMT\r\n" +
            "Accept: application/json\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n";

    /**
     * 示例13: 条件请求 - If-None-Match (ETag)
     * 使用ETag进行缓存验证
     */
    public static final String CONDITIONAL_REQUEST_IF_NONE_MATCH = "" +
            "GET /api/data HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "If-None-Match: \"33a64df551425fcc55e4d42a148795d9f25f89d4\"\r\n" +
            "Accept: application/json\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n";

    /**
     * 示例14: OPTIONS请求 - CORS预检
     * 跨域请求预检
     */
    public static final String OPTIONS_REQUEST = "" +
            "OPTIONS /api/resource HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "Access-Control-Request-Method: POST\r\n" +
            "Access-Control-Request-Headers: Content-Type\r\n" +
            "Origin: http://localhost:3000\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n";

    /**
     * 示例15: HEAD请求 - 只获取响应头
     * 检查资源是否存在和大小
     */
    public static final String HEAD_REQUEST = "" +
            "HEAD /api/resource HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "Accept: */*\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n";

    /**
     * 示例16: 大文件上传请求
     * 上传二进制文件（图片）
     */
    public static final String LARGE_FILE_UPLOAD_REQUEST = "" +
            "POST /upload HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "Content-Type: multipart/form-data; boundary=----WebKitFormBoundary7MA4YWxkTrZu0gW\r\n" +
            "Content-Length: 320\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n" +
            "------WebKitFormBoundary7MA4YWxkTrZu0gW\r\n" +
            "Content-Disposition: form-data; name=\"file\"; filename=\"image.png\"\r\n" +
            "Content-Type: image/png\r\n" +
            "\r\n" +
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==\r\n" +
            "------WebKitFormBoundary7MA4YWxkTrZu0gW--\r\n";

    /**
     * 示例17: 带自定义Header的请求
     * 包含自定义头部字段
     */
    public static final String REQUEST_WITH_CUSTOM_HEADERS = "" +
            "GET /api/custom HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "X-Request-ID: 12345-67890-abcdef\r\n" +
            "X-Client-Version: 2.5.1\r\n" +
            "X-Device-ID: device_abc_123\r\n" +
            "Accept: application/json\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n";

    /**
     * 示例18: Chunked传输编码请求
     * 分块传输编码的POST请求
     */
    public static final String CHUNKED_REQUEST = "" +
            "POST /api/chunked HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "Content-Type: application/json\r\n" +
            "Transfer-Encoding: chunked\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n" +
            "1a\r\n" +
            "{\"message\":\"Hello World\"}\r\n" +
            "0\r\n" +
            "\r\n";

    /**
     * 示例19: 带压缩的请求
     * Accept-Encoding请求压缩响应
     */
    public static final String REQUEST_WITH_COMPRESSION = "" +
            "GET /api/data HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "Accept-Encoding: gzip, deflate, br\r\n" +
            "Accept: application/json\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n";

    /**
     * 示例20: PATCH请求 - 部分更新
     * 部分更新资源
     */
    public static final String PATCH_REQUEST = "" +
            "PATCH /api/user/1001 HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "Content-Type: application/json-patch+json\r\n" +
            "Content-Length: 98\r\n" +
            "Accept: application/json\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n" +
            "[{\"op\":\"replace\",\"path\":\"/age\",\"value\":27},{\"op\":\"replace\",\"path\":\"/city\",\"value\":\"Guangzhou\"}]";

    /**
     * 示例21: WebSocket升级请求
     * HTTP协议升级到WebSocket
     */
    public static final String WEBSOCKET_UPGRADE_REQUEST = "" +
            "GET /chat HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
            "Sec-WebSocket-Version: 13\r\n" +
            "Sec-WebSocket-Protocol: chat\r\n" +
            "Origin: http://localhost:3000\r\n" +
            "\r\n";

    /**
     * 示例22: Basic认证请求
     * HTTP Basic Authentication
     */
    public static final String BASIC_AUTH_REQUEST = "" +
            "GET /api/protected HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "Authorization: Basic am9objpzZWNyZXQxMjM=\r\n" +
            "Accept: application/json\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n";

    /**
     * 示例23: POST XML数据请求
     * 提交XML格式的数据
     */
    public static final String POST_XML_REQUEST = "" +
            "POST /api/user/create HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "Content-Type: application/xml;charset=utf-8\r\n" +
            "Content-Length: 145\r\n" +
            "Accept: application/xml\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n" +
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><user><id>1003</id><name>Bob</name><email>bob@example.com</email><age>30</age></user>";

    /**
     * 示例24: 多文件上传请求
     * 同时上传多个文件
     */
    public static final String MULTIPLE_FILES_UPLOAD_REQUEST = "" +
            "POST /upload/multiple HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "Content-Type: multipart/form-data; boundary=----WebKitFormBoundary7MA4YWxkTrZu0gW\r\n" +
            "Content-Length: 452\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n" +
            "------WebKitFormBoundary7MA4YWxkTrZu0gW\r\n" +
            "Content-Disposition: form-data; name=\"file1\"; filename=\"file1.txt\"\r\n" +
            "Content-Type: text/plain\r\n" +
            "\r\n" +
            "Content of file 1\r\n" +
            "------WebKitFormBoundary7MA4YWxkTrZu0gW\r\n" +
            "Content-Disposition: form-data; name=\"file2\"; filename=\"file2.txt\"\r\n" +
            "Content-Type: text/plain\r\n" +
            "\r\n" +
            "Content of file 2\r\n" +
            "------WebKitFormBoundary7MA4YWxkTrZu0gW\r\n" +
            "Content-Disposition: form-data; name=\"description\"\r\n" +
            "\r\n" +
            "Multiple files upload test\r\n" +
            "------WebKitFormBoundary7MA4YWxkTrZu0gW--\r\n";

    /**
     * 示例25: HTTP/1.0请求
     * 使用HTTP/1.0协议的请求
     */
    public static final String HTTP10_REQUEST = "" +
            "GET /index.html HTTP/1.0\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/4.0\r\n" +
            "Accept: text/html\r\n" +
            "\r\n";

    /**
     * 示例26: Expect: 100-Continue请求
     * 在发送大请求体前先确认服务器愿意接收
     */
    public static final String EXPECT_CONTINUE_REQUEST = "" +
            "POST /api/upload HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: 1024\r\n" +
            "Expect: 100-continue\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n" +
            "{\"large\":\"payload\"}";

    /**
     * 示例27: 带Referer的请求
     * 表示请求来源
     */
    public static final String REQUEST_WITH_REFERER = "" +
            "GET /api/resource HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "Referer: http://localhost:8080/previous-page\r\n" +
            "Accept: application/json\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n";

    /**
     * 示例28: TRACE请求
     * 用于调试，回显服务器收到的请求
     */
    public static final String TRACE_REQUEST = "" +
            "TRACE /api/test HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "Connection: close\r\n" +
            "\r\n";

    /**
     * 示例29: 强制刷新缓存请求
     * 强制绕过缓存
     */
    public static final String FORCE_REFRESH_REQUEST = "" +
            "GET /api/data HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "Cache-Control: no-cache, no-store, max-age=0\r\n" +
            "Pragma: no-cache\r\n" +
            "Accept: application/json\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n";

    /**
     * 示例30: 带User-Agent详细信息的请求
     * 完整的User-Agent头
     */
    public static final String REQUEST_WITH_DETAILED_USER_AGENT = "" +
            "GET /api/info HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0\r\n" +
            "Accept: */*\r\n" +
            "Accept-Language: zh-CN,zh;q=0.9,en;q=0.8\r\n" +
            "Accept-Encoding: gzip, deflate, br\r\n" +
            "Connection: keep-alive\r\n" +
            "Upgrade-Insecure-Requests: 1\r\n" +
            "\r\n";

    // ==================== HTTP响应报文示例 ====================

    /**
     * 响应示例1: 200 OK - 成功响应
     * 简单的成功响应
     */
    public static final String RESPONSE_200_OK = "" +
            "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/json;charset=utf-8\r\n" +
            "Content-Length: 27\r\n" +
            "Date: Thu, 19 Mar 2026 08:00:00 GMT\r\n" +
            "Server: wastnet-http/1.0\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n" +
            "{\"status\":\"success\"}";

    /**
     * 响应示例2: 201 Created - 资源创建成功
     * POST请求成功创建资源
     */
    public static final String RESPONSE_201_CREATED = "" +
            "HTTP/1.1 201 Created\r\n" +
            "Content-Type: application/json;charset=utf-8\r\n" +
            "Content-Length: 95\r\n" +
            "Location: /api/user/1001\r\n" +
            "Date: Thu, 19 Mar 2026 08:00:00 GMT\r\n" +
            "Server: wastnet-http/1.0\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n" +
            "{\"status\":\"created\",\"id\":1001,\"message\":\"Resource created successfully\"}";

    /**
     * 响应示例3: 204 No Content - 成功但无内容返回
     * DELETE或PUT成功后的响应
     */
    public static final String RESPONSE_204_NO_CONTENT = "" +
            "HTTP/1.1 204 No Content\r\n" +
            "Date: Thu, 19 Mar 2026 08:00:00 GMT\r\n" +
            "Server: wastnet-http/1.0\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n";

    /**
     * 响应示例4: 301 Moved Permanently - 永久重定向
     * 资源已永久移动
     */
    public static final String RESPONSE_301_MOVED_PERMANENTLY = "" +
            "HTTP/1.1 301 Moved Permanently\r\n" +
            "Location: https://www.example.com/new-url\r\n" +
            "Content-Length: 0\r\n" +
            "Date: Thu, 19 Mar 2026 08:00:00 GMT\r\n" +
            "Server: wastnet-http/1.0\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n";

    /**
     * 响应示例5: 302 Found - 临时重定向
     * 资源临时移动
     */
    public static final String RESPONSE_302_FOUND = "" +
            "HTTP/1.1 302 Found\r\n" +
            "Location: /login\r\n" +
            "Content-Length: 0\r\n" +
            "Date: Thu, 19 Mar 2026 08:00:00 GMT\r\n" +
            "Server: wastnet-http/1.0\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n";

    /**
     * 响应示例6: 304 Not Modified - 缓存命中
     * 资源未修改，使用缓存
     */
    public static final String RESPONSE_304_NOT_MODIFIED = "" +
            "HTTP/1.1 304 Not Modified\r\n" +
            "Date: Thu, 19 Mar 2026 08:00:00 GMT\r\n" +
            "ETag: \"33a64df551425fcc55e4d42a148795d9f25f89d4\"\r\n" +
            "Cache-Control: max-age=3600\r\n" +
            "Server: wastnet-http/1.0\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n";

    /**
     * 响应示例7: 400 Bad Request - 请求错误
     * 客户端请求格式错误
     */
    public static final String RESPONSE_400_BAD_REQUEST = "" +
            "HTTP/1.1 400 Bad Request\r\n" +
            "Content-Type: application/json;charset=utf-8\r\n" +
            "Content-Length: 58\r\n" +
            "Date: Thu, 19 Mar 2026 08:00:00 GMT\r\n" +
            "Server: wastnet-http/1.0\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n" +
            "{\"status\":\"error\",\"message\":\"Invalid request format\"}";

    /**
     * 响应示例8: 401 Unauthorized - 未授权
     * 需要认证
     */
    public static final String RESPONSE_401_UNAUTHORIZED = "" +
            "HTTP/1.1 401 Unauthorized\r\n" +
            "Content-Type: application/json;charset=utf-8\r\n" +
            "Content-Length: 76\r\n" +
            "WWW-Authenticate: Bearer realm=\"api\"\r\n" +
            "Date: Thu, 19 Mar 2026 08:00:00 GMT\r\n" +
            "Server: wastnet-http/1.0\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n" +
            "{\"status\":\"error\",\"message\":\"Authentication required\"}";

    /**
     * 响应示例9: 403 Forbidden - 禁止访问
     * 认证成功但无权限
     */
    public static final String RESPONSE_403_FORBIDDEN = "" +
            "HTTP/1.1 403 Forbidden\r\n" +
            "Content-Type: application/json;charset=utf-8\r\n" +
            "Content-Length: 62\r\n" +
            "Date: Thu, 19 Mar 2026 08:00:00 GMT\r\n" +
            "Server: wastnet-http/1.0\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n" +
            "{\"status\":\"error\",\"message\":\"Access denied\"}";

    /**
     * 响应示例10: 404 Not Found - 资源不存在
     */
    public static final String RESPONSE_404_NOT_FOUND = "" +
            "HTTP/1.1 404 Not Found\r\n" +
            "Content-Type: application/json;charset=utf-8\r\n" +
            "Content-Length: 59\r\n" +
            "Date: Thu, 19 Mar 2026 08:00:00 GMT\r\n" +
            "Server: wastnet-http/1.0\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n" +
            "{\"status\":\"error\",\"message\":\"Resource not found\"}";

    /**
     * 响应示例11: 500 Internal Server Error - 服务器内部错误
     */
    public static final String RESPONSE_500_INTERNAL_SERVER_ERROR = "" +
            "HTTP/1.1 500 Internal Server Error\r\n" +
            "Content-Type: application/json;charset=utf-8\r\n" +
            "Content-Length: 70\r\n" +
            "Date: Thu, 19 Mar 2026 08:00:00 GMT\r\n" +
            "Server: wastnet-http/1.0\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            "{\"status\":\"error\",\"message\":\"Internal server error\"}";

    /**
     * 响应示例12: 503 Service Unavailable - 服务不可用
     */
    public static final String RESPONSE_503_SERVICE_UNAVAILABLE = "" +
            "HTTP/1.1 503 Service Unavailable\r\n" +
            "Content-Type: application/json;charset=utf-8\r\n" +
            "Content-Length: 63\r\n" +
            "Retry-After: 60\r\n" +
            "Date: Thu, 19 Mar 2026 08:00:00 GMT\r\n" +
            "Server: wastnet-http/1.0\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            "{\"status\":\"error\",\"message\":\"Service temporarily unavailable\"}";

    /**
     * 响应示例13: Range响应 - 部分内容
     * 支持断点续传
     */
    public static final String RESPONSE_206_PARTIAL_CONTENT = "" +
            "HTTP/1.1 206 Partial Content\r\n" +
            "Content-Type: application/octet-stream\r\n" +
            "Content-Length: 1024\r\n" +
            "Content-Range: bytes 0-1023/10485760\r\n" +
            "Accept-Ranges: bytes\r\n" +
            "Date: Thu, 19 Mar 2026 08:00:00 GMT\r\n" +
            "Server: wastnet-http/1.0\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n";

    /**
     * 响应示例14: 100 Continue - 继续
     * 服务器愿意接收请求体
     */
    public static final String RESPONSE_100_CONTINUE = "" +
            "HTTP/1.1 100 Continue\r\n" +
            "Date: Thu, 19 Mar 2026 08:00:00 GMT\r\n" +
            "Server: wastnet-http/1.0\r\n" +
            "\r\n";

    /**
     * 响应示例15: Gzip压缩响应
     * 返回压缩的内容
     */
    public static final String RESPONSE_WITH_GZIP = "" +
            "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/json;charset=utf-8\r\n" +
            "Content-Encoding: gzip\r\n" +
            "Content-Length: 52\r\n" +
            "Date: Thu, 19 Mar 2026 08:00:00 GMT\r\n" +
            "Server: wastnet-http/1.0\r\n" +
            "Connection: keep-alive\r\n" +
            "Vary: Accept-Encoding\r\n" +
            "\r\n" +
            "H4sIAAAAAAAAAy3JzQrCMBAE0FfxX5a1dO0kW6jUkEwH7Cq1VW3rK5JgEAAAA=";

    /**
     * 响应示例16: Chunked传输编码响应
     * 分块传输
     */
    public static final String RESPONSE_CHUNKED = "" +
            "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/plain;charset=utf-8\r\n" +
            "Transfer-Encoding: chunked\r\n" +
            "Date: Thu, 19 Mar 2026 08:00:00 GMT\r\n" +
            "Server: wastnet-http/1.0\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n" +
            "5\r\n" +
            "Hello\r\n" +
            "6\r\n" +
            " World\r\n" +
            "0\r\n" +
            "\r\n";

    /**
     * 响应示例17: 带Set-Cookie的响应
     * 设置Cookie
     */
    public static final String RESPONSE_WITH_COOKIE = "" +
            "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/json;charset=utf-8\r\n" +
            "Content-Length: 27\r\n" +
            "Set-Cookie: sessionId=abc123def456; Path=/; HttpOnly; Secure; SameSite=Strict\r\n" +
            "Set-Cookie: userId=1001; Path=/; Max-Age=3600\r\n" +
            "Date: Thu, 19 Mar 2026 08:00:00 GMT\r\n" +
            "Server: wastnet-http/1.0\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n" +
            "{\"status\":\"success\"}";

    /**
     * 响应示例18: CORS响应
     * 跨域资源共享
     */
    public static final String RESPONSE_CORS_PREFLIGHT = "" +
            "HTTP/1.1 200 OK\r\n" +
            "Access-Control-Allow-Origin: http://localhost:3000\r\n" +
            "Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS\r\n" +
            "Access-Control-Allow-Headers: Content-Type, Authorization\r\n" +
            "Access-Control-Max-Age: 86400\r\n" +
            "Content-Length: 0\r\n" +
            "Date: Thu, 19 Mar 2026 08:00:00 GMT\r\n" +
            "Server: wastnet-http/1.0\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n";

    /**
     * 响应示例19: WebSocket升级响应
     * 协议升级到WebSocket
     */
    public static final String RESPONSE_WEBSOCKET_UPGRADE = "" +
            "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n" +
            "Sec-WebSocket-Protocol: chat\r\n" +
            "Date: Thu, 19 Mar 2026 08:00:00 GMT\r\n" +
            "Server: wastnet-http/1.0\r\n" +
            "\r\n";

    /**
     * 响应示例20: HTML页面响应
     * 返回HTML内容
     */
    public static final String RESPONSE_HTML = "" +
            "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/html;charset=utf-8\r\n" +
            "Content-Length: 194\r\n" +
            "Date: Thu, 19 Mar 2026 08:00:00 GMT\r\n" +
            "Server: wastnet-http/1.0\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n" +
            "<!DOCTYPE html><html><head><title>Test Page</title></head><body><h1>Hello World</h1><p>This is a test HTML page.</p></body></html>";

    /**
     * 响应示例21: 429 Too Many Requests - 请求过多
     * 触发限流
     */
    public static final String RESPONSE_429_TOO_MANY_REQUESTS = "" +
            "HTTP/1.1 429 Too Many Requests\r\n" +
            "Content-Type: application/json;charset=utf-8\r\n" +
            "Content-Length: 68\r\n" +
            "Retry-After: 30\r\n" +
            "X-RateLimit-Limit: 100\r\n" +
            "X-RateLimit-Remaining: 0\r\n" +
            "X-RateLimit-Reset: 1710816000\r\n" +
            "Date: Thu, 19 Mar 2026 08:00:00 GMT\r\n" +
            "Server: wastnet-http/1.0\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n" +
            "{\"status\":\"error\",\"message\":\"Rate limit exceeded\"}";

    /**
     * 响应示例22: 502 Bad Gateway - 网关错误
     * 上游服务器无响应
     */
    public static final String RESPONSE_502_BAD_GATEWAY = "" +
            "HTTP/1.1 502 Bad Gateway\r\n" +
            "Content-Type: application/json;charset=utf-8\r\n" +
            "Content-Length: 65\r\n" +
            "Date: Thu, 19 Mar 2026 08:00:00 GMT\r\n" +
            "Server: wastnet-http/1.0\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            "{\"status\":\"error\",\"message\":\"Bad gateway\"}";

    /**
     * 响应示例23: 504 Gateway Timeout - 网关超时
     * 上游服务器响应超时
     */
    public static final String RESPONSE_504_GATEWAY_TIMEOUT = "" +
            "HTTP/1.1 504 Gateway Timeout\r\n" +
            "Content-Type: application/json;charset=utf-8\r\n" +
            "Content-Length: 66\r\n" +
            "Date: Thu, 19 Mar 2026 08:00:00 GMT\r\n" +
            "Server: wastnet-http/1.0\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            "{\"status\":\"error\",\"message\":\"Gateway timeout\"}";

    /**
     * 响应示例24: 409 Conflict - 资源冲突
     * 资源状态冲突
     */
    public static final String RESPONSE_409_CONFLICT = "" +
            "HTTP/1.1 409 Conflict\r\n" +
            "Content-Type: application/json;charset=utf-8\r\n" +
            "Content-Length: 78\r\n" +
            "Date: Thu, 19 Mar 2026 08:00:00 GMT\r\n" +
            "Server: wastnet-http/1.0\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n" +
            "{\"status\":\"error\",\"message\":\"Resource conflict\"}";

    /**
     * 响应示例25: 422 Unprocessable Entity - 无法处理的实体
     * 请求格式正确但语义错误
     */
    public static final String RESPONSE_422_UNPROCESSABLE_ENTITY = "" +
            "HTTP/1.1 422 Unprocessable Entity\r\n" +
            "Content-Type: application/json;charset=utf-8\r\n" +
            "Content-Length: 84\r\n" +
            "Date: Thu, 19 Mar 2026 08:00:00 GMT\r\n" +
            "Server: wastnet-http/1.0\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n" +
            "{\"status\":\"error\",\"message\":\"Unprocessable entity\"}";

    // ==================== 更多HTTP请求示例 ====================

    /**
     * 示例31: POST 二进制数据请求
     * 提交protobuf或其他二进制格式
     */
    public static final String POST_BINARY_DATA_REQUEST = "" +
            "POST /api/data/binary HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "Content-Type: application/octet-stream\r\n" +
            "Content-Length: 16\r\n" +
            "Accept: application/octet-stream\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n" +
            "0a0d48656c6c6f20576f726c640a";

    /**
     * 示例32: 多范围请求
     * 同时请求多个文件片段
     */
    public static final String MULTI_RANGE_REQUEST = "" +
            "GET /download/file.zip HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "Range: bytes=0-1023,2048-3071,4096-5119\r\n" +
            "Accept: */*\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n";

    /**
     * 示例33: 条件请求 - If-Match
     * 使用ETag确保资源未修改
     */
    public static final String CONDITIONAL_REQUEST_IF_MATCH = "" +
            "PUT /api/user/1001 HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: 45\r\n" +
            "If-Match: \"33a64df551425fcc55e4d42a148795d9f25f89d4\"\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n" +
            "{\"name\":\"John\",\"age\":26}";

    /**
     * 示例34: 条件请求 - If-Unmodified-Since
     * 确保资源在指定时间后未被修改
     */
    public static final String CONDITIONAL_REQUEST_IF_UNMODIFIED_SINCE = "" +
            "PUT /api/user/1001 HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: 45\r\n" +
            "If-Unmodified-Since: Wed, 21 Oct 2015 07:28:00 GMT\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n" +
            "{\"name\":\"John\",\"age\":26}";

    /**
     * 示例35: 条件请求 - If-Range
     * 如果ETag匹配则返回Range，否则返回完整资源
     */
    public static final String CONDITIONAL_REQUEST_IF_RANGE = "" +
            "GET /download/file.zip HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "Range: bytes=1024-\r\n" +
            "If-Range: \"33a64df551425fcc55e4d42a148795d9f25f89d4\"\r\n" +
            "Accept: */*\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n";

    /**
     * 示例36: 代理请求
     * 通过代理服务器访问资源
     */
    public static final String PROXY_REQUEST = "" +
            "GET http://example.com/api/data HTTP/1.1\r\n" +
            "Host: example.com\r\n" +
            "Proxy-Connection: keep-alive\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "Proxy-Authorization: Basic dXNlcjpwYXNz\r\n" +
            "Accept: application/json\r\n" +
            "\r\n";

    /**
     * 示例37: 带Keep-Alive参数的请求
     * 指定持久连接的超时和最大请求数
     */
    public static final String REQUEST_WITH_KEEP_ALIVE_PARAMS = "" +
            "GET /api/data HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "Connection: keep-alive\r\n" +
            "Keep-Alive: timeout=30, max=100\r\n" +
            "Accept: application/json\r\n" +
            "\r\n";

    /**
     * 示例38: 分页请求
     * 带分页参数的请求
     */
    public static final String PAGINATION_REQUEST = "" +
            "GET /api/users?page=2&limit=20&sort=name&order=asc HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "Accept: application/json\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n";

    /**
     * 示例39: WebDAV PROPFIND请求
     * 查询资源属性
     */
    public static final String WEBDAV_PROPFIND_REQUEST = "" +
            "PROPFIND /files/ HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "Depth: 1\r\n" +
            "Content-Type: application/xml;charset=utf-8\r\n" +
            "Content-Length: 123\r\n" +
            "\r\n" +
            "<?xml version=\"1.0\" encoding=\"utf-8\" ?><D:propfind xmlns:D=\"DAV:\"><D:prop><D:getlastmodified/></D:prop></D:propfind>";

    /**
     * 示例40: 带DNT的请求
     * Do Not Track - 请求不被追踪
     */
    public static final String REQUEST_WITH_DNT = "" +
            "GET /api/data HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "DNT: 1\r\n" +
            "Accept: application/json\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n";

    /**
     * 示例41: 带X-Forwarded-For的请求
     * 表示原始客户端IP
     */
    public static final String REQUEST_WITH_X_FORWARDED_FOR = "" +
            "GET /api/data HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "X-Forwarded-For: 203.0.113.195, 70.41.3.18, 150.172.238.178\r\n" +
            "X-Forwarded-Proto: https\r\n" +
            "X-Forwarded-Host: example.com\r\n" +
            "Accept: application/json\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n";

    /**
     * 示例42: 带Sec-Fetch的请求
     * 安全相关的元数据
     */
    public static final String REQUEST_WITH_SEC_FETCH = "" +
            "GET /api/data HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "Sec-Fetch-Dest: empty\r\n" +
            "Sec-Fetch-Mode: cors\r\n" +
            "Sec-Fetch-Site: cross-site\r\n" +
            "Accept: application/json\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n";

    /**
     * 示例43: POST YAML数据请求
     * 提交YAML格式的数据
     */
    public static final String POST_YAML_REQUEST = "" +
            "POST /api/config HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "Content-Type: application/x-yaml\r\n" +
            "Content-Length: 58\r\n" +
            "Accept: application/x-yaml\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n" +
            "name: John\r\n" +
            "age: 25\r\n" +
            "city: Beijing";

    /**
     * 示例44: 带优先级的请求
     * HTTP/2 或 HTTP/3 风格的优先级提示
     */
    public static final String REQUEST_WITH_PRIORITY = "" +
            "GET /api/data HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "Priority: u=5\r\n" +
            "Accept: application/json\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n";

    /**
     * 示例45: 带内容协商的请求
     * 指定语言和字符集偏好
     */
    public static final String REQUEST_WITH_CONTENT_NEGOTIATION = "" +
            "GET /api/data HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8\r\n" +
            "Accept-Language: zh-CN,zh;q=0.9,en;q=0.8,fr;q=0.7\r\n" +
            "Accept-Charset: utf-8, iso-8859-1;q=0.5\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n";

    /**
     * 响应示例26: 多范围响应
     * 多部分/字节范围响应
     */
    public static final String RESPONSE_MULTI_RANGE = "" +
            "HTTP/1.1 206 Partial Content\r\n" +
            "Content-Type: multipart/byteranges; boundary=THIS_STRING_SEPARATES\r\n" +
            "Content-Length: 285\r\n" +
            "Date: Thu, 19 Mar 2026 08:00:00 GMT\r\n" +
            "Server: wastnet-http/1.0\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n" +
            "--THIS_STRING_SEPARATES\r\n" +
            "Content-Type: application/octet-stream\r\n" +
            "Content-Range: bytes 0-1023/10485760\r\n" +
            "\r\n" +
            "First chunk data...\r\n" +
            "--THIS_STRING_SEPARATES\r\n" +
            "Content-Type: application/octet-stream\r\n" +
            "Content-Range: bytes 2048-3071/10485760\r\n" +
            "\r\n" +
            "Second chunk data...\r\n" +
            "--THIS_STRING_SEPARATES--\r\n";

    /**
     * 响应示例27: 405 Method Not Allowed - 方法不允许
     */
    public static final String RESPONSE_405_METHOD_NOT_ALLOWED = "" +
            "HTTP/1.1 405 Method Not Allowed\r\n" +
            "Content-Type: application/json;charset=utf-8\r\n" +
            "Content-Length: 66\r\n" +
            "Allow: GET, POST, PUT\r\n" +
            "Date: Thu, 19 Mar 2026 08:00:00 GMT\r\n" +
            "Server: wastnet-http/1.0\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n" +
            "{\"status\":\"error\",\"message\":\"Method not allowed\"}";

    /**
     * 响应示例28: 408 Request Timeout - 请求超时
     */
    public static final String RESPONSE_408_REQUEST_TIMEOUT = "" +
            "HTTP/1.1 408 Request Timeout\r\n" +
            "Content-Type: application/json;charset=utf-8\r\n" +
            "Content-Length: 66\r\n" +
            "Date: Thu, 19 Mar 2026 08:00:00 GMT\r\n" +
            "Server: wastnet-http/1.0\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            "{\"status\":\"error\",\"message\":\"Request timeout\"}";

    /**
     * 响应示例29: 410 Gone - 资源已永久删除
     */
    public static final String RESPONSE_410_GONE = "" +
            "HTTP/1.1 410 Gone\r\n" +
            "Content-Type: application/json;charset=utf-8\r\n" +
            "Content-Length: 62\r\n" +
            "Date: Thu, 19 Mar 2026 08:00:00 GMT\r\n" +
            "Server: wastnet-http/1.0\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n" +
            "{\"status\":\"error\",\"message\":\"Resource gone\"}";

    /**
     * 响应示例30: 414 URI Too Long - URI过长
     */
    public static final String RESPONSE_414_URI_TOO_LONG = "" +
            "HTTP/1.1 414 URI Too Long\r\n" +
            "Content-Type: application/json;charset=utf-8\r\n" +
            "Content-Length: 64\r\n" +
            "Date: Thu, 19 Mar 2026 08:00:00 GMT\r\n" +
            "Server: wastnet-http/1.0\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            "{\"status\":\"error\",\"message\":\"URI too long\"}";

    // ==================== 更多HTTP请求示例 ====================

    /**
     * 示例46: Digest认证请求
     * HTTP Digest Authentication
     */
    public static final String DIGEST_AUTH_REQUEST = "" +
            "GET /api/protected HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "Authorization: Digest username=\"john\", realm=\"Protected Area\", nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", uri=\"/api/protected\", response=\"6629fae49393a05397450978507c4ef1\", opaque=\"5ccc069c403ebaf9f0171e9517f40e41\"\r\n" +
            "Accept: application/json\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n";

    /**
     * 示例47: POST CSV数据请求
     * 提交CSV格式的数据
     */
    public static final String POST_CSV_REQUEST = "" +
            "POST /api/import HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "Content-Type: text/csv\r\n" +
            "Content-Length: 68\r\n" +
            "Accept: application/json\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n" +
            "id,name,email,age\r\n" +
            "1001,John,john@example.com,25\r\n" +
            "1002,Alice,alice@example.com,28";

    /**
     * 示例48: GraphQL请求
     * GraphQL查询
     */
    public static final String GRAPHQL_REQUEST = "" +
            "POST /graphql HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: 95\r\n" +
            "Accept: application/json\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n" +
            "{\"query\":\"{ user(id: 1001) { name email age } }\",\"variables\":{},\"operationName\":null}";

    /**
     * 示例49: Server-Sent Events (SSE)请求
     * 订阅服务器推送事件
     */
    public static final String SSE_REQUEST = "" +
            "GET /api/events HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "Accept: text/event-stream\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n";

    /**
     * 示例50: POST MessagePack数据请求
     * 提交MessagePack二进制格式
     */
    public static final String POST_MESSAGEPACK_REQUEST = "" +
            "POST /api/data/msgpack HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "Content-Type: application/msgpack\r\n" +
            "Content-Length: 18\r\n" +
            "Accept: application/msgpack\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n" +
            "81a46e616d65a44a6f686e";

    /**
     * 示例51: API Key认证请求
     * 使用API Key进行认证
     */
    public static final String API_KEY_AUTH_REQUEST = "" +
            "GET /api/data HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "X-API-Key: abc123def456ghi789\r\n" +
            "Accept: application/json\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n";

    /**
     * 示例52: 带From的请求
     * 标识请求发起者的邮箱
     */
    public static final String REQUEST_WITH_FROM = "" +
            "GET /api/data HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "From: bot@example.com\r\n" +
            "Accept: application/json\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n";

    /**
     * 示例53: 带TE的请求
     * 请求使用传输编码
     */
    public static final String REQUEST_WITH_TE = "" +
            "GET /api/data HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "TE: deflate, chunked\r\n" +
            "Accept: application/json\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n";

    /**
     * 示例54: 带Max-Forwards的请求
     * 限制代理跳转次数
     */
    public static final String REQUEST_WITH_MAX_FORWARDS = "" +
            "TRACE /api/data HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "Max-Forwards: 10\r\n" +
            "Connection: close\r\n" +
            "\r\n";

    /**
     * 示例55: 带Via的请求
     * 标识通过的代理服务器
     */
    public static final String REQUEST_WITH_VIA = "" +
            "GET /api/data HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "Via: 1.0 fred, 1.1 nowhere.com (Apache/1.1)\r\n" +
            "Accept: application/json\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n";

    /**
     * 示例56: PATCH部分更新请求
     * JSON Merge Patch格式
     */
    public static final String PATCH_MERGE_REQUEST = "" +
            "PATCH /api/user/1001 HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "Content-Type: application/merge-patch+json\r\n" +
            "Content-Length: 32\r\n" +
            "Accept: application/json\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n" +
            "{\"age\":27,\"city\":\"Guangzhou\"}";

    /**
     * 示例57: 带Warning的请求
     * 警告信息
     */
    public static final String REQUEST_WITH_WARNING = "" +
            "GET /api/data HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "Warning: 199 Miscellaneous \"Deprecated API\" HTTP/1.1\r\n" +
            "Accept: application/json\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n";

    /**
     * 示例58: 大Range请求
     * 请求从某个偏移量开始的所有数据
     */
    public static final String LARGE_RANGE_REQUEST = "" +
            "GET /download/file.zip HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "Range: bytes=1024-\r\n" +
            "Accept: */*\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n";

    /**
     * 示例59: 最后N字节的Range请求
     * 请求文件最后N个字节
     */
    public static final String LAST_BYTES_RANGE_REQUEST = "" +
            "GET /download/file.zip HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "Range: bytes=-1024\r\n" +
            "Accept: */*\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n";

    /**
     * 示例60: POST FormData带数组
     * 提交包含数组数据的表单
     */
    public static final String POST_FORM_WITH_ARRAY = "" +
            "POST /api/users/create HTTP/1.1\r\n" +
            "Host: localhost:8080\r\n" +
            "User-Agent: Mozilla/5.0\r\n" +
            "Content-Type: application/x-www-form-urlencoded\r\n" +
            "Content-Length: 67\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n" +
            "name=Team&members%5B0%5D=John&members%5B1%5D=Alice&members%5B2%5D=Bob";

    // ==================== 更多HTTP响应示例 ====================

    /**
     * 响应示例31: 303 See Other - 重定向
     */
    public static final String RESPONSE_303_SEE_OTHER = "" +
            "HTTP/1.1 303 See Other\r\n" +
            "Location: /api/redirected\r\n" +
            "Content-Length: 0\r\n" +
            "Date: Thu, 19 Mar 2026 08:00:00 GMT\r\n" +
            "Server: wastnet-http/1.0\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n";

    /**
     * 响应示例32: 307 Temporary Redirect - 临时重定向
     * 保持请求方法和body
     */
    public static final String RESPONSE_307_TEMPORARY_REDIRECT = "" +
            "HTTP/1.1 307 Temporary Redirect\r\n" +
            "Location: /api/new-location\r\n" +
            "Content-Length: 0\r\n" +
            "Date: Thu, 19 Mar 2026 08:00:00 GMT\r\n" +
            "Server: wastnet-http/1.0\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n";

    /**
     * 响应示例33: 308 Permanent Redirect - 永久重定向
     * 保持请求方法和body
     */
    public static final String RESPONSE_308_PERMANENT_REDIRECT = "" +
            "HTTP/1.1 308 Permanent Redirect\r\n" +
            "Location: https://newapi.example.com/resource\r\n" +
            "Content-Length: 0\r\n" +
            "Date: Thu, 19 Mar 2026 08:00:00 GMT\r\n" +
            "Server: wastnet-http/1.0\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n";

    /**
     * 响应示例34: 415 Unsupported Media Type - 不支持的媒体类型
     */
    public static final String RESPONSE_415_UNSUPPORTED_MEDIA_TYPE = "" +
            "HTTP/1.1 415 Unsupported Media Type\r\n" +
            "Content-Type: application/json;charset=utf-8\r\n" +
            "Content-Length: 70\r\n" +
            "Date: Thu, 19 Mar 2026 08:00:00 GMT\r\n" +
            "Server: wastnet-http/1.0\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n" +
            "{\"status\":\"error\",\"message\":\"Unsupported media type\"}";

    /**
     * 响应示例35: 416 Range Not Satisfiable - 范围不可满足
     */
    public static final String RESPONSE_416_RANGE_NOT_SATISFIABLE = "" +
            "HTTP/1.1 416 Range Not Satisfiable\r\n" +
            "Content-Range: bytes */10485760\r\n" +
            "Content-Type: application/json;charset=utf-8\r\n" +
            "Content-Length: 67\r\n" +
            "Date: Thu, 19 Mar 2026 08:00:00 GMT\r\n" +
            "Server: wastnet-http/1.0\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n" +
            "{\"status\":\"error\",\"message\":\"Range not satisfiable\"}";

    /**
     * 响应示例36: 417 Expectation Failed - 期望失败
     */
    public static final String RESPONSE_417_EXPECTATION_FAILED = "" +
            "HTTP/1.1 417 Expectation Failed\r\n" +
            "Content-Type: application/json;charset=utf-8\r\n" +
            "Content-Length: 71\r\n" +
            "Date: Thu, 19 Mar 2026 08:00:00 GMT\r\n" +
            "Server: wastnet-http/1.0\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n" +
            "{\"status\":\"error\",\"message\":\"Expectation failed\"}";

    /**
     * 响应示例37: 426 Upgrade Required - 需要升级协议
     */
    public static final String RESPONSE_426_UPGRADE_REQUIRED = "" +
            "HTTP/1.1 426 Upgrade Required\r\n" +
            "Upgrade: HTTP/2.0, HTTP/3.0\r\n" +
            "Content-Length: 0\r\n" +
            "Date: Thu, 19 Mar 2026 08:00:00 GMT\r\n" +
            "Server: wastnet-http/1.0\r\n" +
            "Connection: close\r\n" +
            "\r\n";

    /**
     * 响应示例38: 428 Precondition Required - 需要前置条件
     */
    public static final String RESPONSE_428_PRECONDITION_REQUIRED = "" +
            "HTTP/1.1 428 Precondition Required\r\n" +
            "Content-Type: application/json;charset=utf-8\r\n" +
            "Content-Length: 75\r\n" +
            "Date: Thu, 19 Mar 2026 08:00:00 GMT\r\n" +
            "Server: wastnet-http/1.0\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n" +
            "{\"status\":\"error\",\"message\":\"Precondition required\"}";

    /**
     * 响应示例39: 451 Unavailable For Legal Reasons - 法律原因不可用
     */
    public static final String RESPONSE_451_UNAVAILABLE_FOR_LEGAL_REASONS = "" +
            "HTTP/1.1 451 Unavailable For Legal Reasons\r\n" +
            "Content-Type: application/json;charset=utf-8\r\n" +
            "Content-Length: 86\r\n" +
            "Date: Thu, 19 Mar 2026 08:00:00 GMT\r\n" +
            "Server: wastnet-http/1.0\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n" +
            "{\"status\":\"error\",\"message\":\"Unavailable for legal reasons\"}";

    /**
     * 响应示例40: 501 Not Implemented - 未实现
     */
    public static final String RESPONSE_501_NOT_IMPLEMENTED = "" +
            "HTTP/1.1 501 Not Implemented\r\n" +
            "Content-Type: application/json;charset=utf-8\r\n" +
            "Content-Length: 65\r\n" +
            "Date: Thu, 19 Mar 2026 08:00:00 GMT\r\n" +
            "Server: wastnet-http/1.0\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            "{\"status\":\"error\",\"message\":\"Not implemented\"}";

    /**
     * 响应示例41: 505 HTTP Version Not Supported - HTTP版本不支持
     */
    public static final String RESPONSE_505_HTTP_VERSION_NOT_SUPPORTED = "" +
            "HTTP/1.1 505 HTTP Version Not Supported\r\n" +
            "Content-Type: application/json;charset=utf-8\r\n" +
            "Content-Length: 76\r\n" +
            "Date: Thu, 19 Mar 2026 08:00:00 GMT\r\n" +
            "Server: wastnet-http/1.0\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            "{\"status\":\"error\",\"message\":\"HTTP version not supported\"}";

    /**
     * 响应示例42: Server-Sent Events (SSE)响应
     * 服务器推送事件
     */
    public static final String RESPONSE_SSE = "" +
            "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/event-stream\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: keep-alive\r\n" +
            "Date: Thu, 19 Mar 2026 08:00:00 GMT\r\n" +
            "Server: wastnet-http/1.0\r\n" +
            "\r\n" +
            "event: message\r\n" +
            "data: Hello World\r\n" +
            "\r\n" +
            "event: update\r\n" +
            "data: {\"id\":1,\"value\":\"test\"}\r\n" +
            "\r\n";

    /**
     * 响应示例43: 带Warning的响应
     * 包含警告信息
     */
    public static final String RESPONSE_WITH_WARNING = "" +
            "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/json;charset=utf-8\r\n" +
            "Content-Length: 27\r\n" +
            "Warning: 199 Miscellaneous \"Deprecated API\" HTTP/1.1\r\n" +
            "Date: Thu, 19 Mar 2026 08:00:00 GMT\r\n" +
            "Server: wastnet-http/1.0\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n" +
            "{\"status\":\"success\"}";

    /**
     * 响应示例44: 带Via的响应
     * 标识通过的代理
     */
    public static final String RESPONSE_WITH_VIA = "" +
            "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/json;charset=utf-8\r\n" +
            "Content-Length: 27\r\n" +
            "Via: 1.0 fred, 1.1 nowhere.com (Apache/1.1)\r\n" +
            "Date: Thu, 19 Mar 2026 08:00:00 GMT\r\n" +
            "Server: wastnet-http/1.0\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n" +
            "{\"status\":\"success\"}";

    /**
     * 响应示例45: 带ETag的响应
     * 资源标识符
     */
    public static final String RESPONSE_WITH_ETAG = "" +
            "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/json;charset=utf-8\r\n" +
            "Content-Length: 56\r\n" +
            "ETag: \"33a64df551425fcc55e4d42a148795d9f25f89d4\"\r\n" +
            "Cache-Control: max-age=3600\r\n" +
            "Date: Thu, 19 Mar 2026 08:00:00 GMT\r\n" +
            "Server: wastnet-http/1.0\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n" +
            "{\"id\":1001,\"name\":\"John\",\"age\":25}";

    /**
     * 响应示例46: 带Last-Modified的响应
     * 最后修改时间
     */
    public static final String RESPONSE_WITH_LAST_MODIFIED = "" +
            "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/json;charset=utf-8\r\n" +
            "Content-Length: 56\r\n" +
            "Last-Modified: Wed, 21 Oct 2015 07:28:00 GMT\r\n" +
            "Date: Thu, 19 Mar 2026 08:00:00 GMT\r\n" +
            "Server: wastnet-http/1.0\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n" +
            "{\"id\":1001,\"name\":\"John\",\"age\":25}";

    /**
     * 响应示例47: 205 Reset Content - 重置内容
     */
    public static final String RESPONSE_205_RESET_CONTENT = "" +
            "HTTP/1.1 205 Reset Content\r\n" +
            "Date: Thu, 19 Mar 2026 08:00:00 GMT\r\n" +
            "Server: wastnet-http/1.0\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n";

    /**
     * 响应示例48: 412 Precondition Failed - 前置条件失败
     */
    public static final String RESPONSE_412_PRECONDITION_FAILED = "" +
            "HTTP/1.1 412 Precondition Failed\r\n" +
            "Content-Type: application/json;charset=utf-8\r\n" +
            "Content-Length: 71\r\n" +
            "Date: Thu, 19 Mar 2026 08:00:00 GMT\r\n" +
            "Server: wastnet-http/1.0\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n" +
            "{\"status\":\"error\",\"message\":\"Precondition failed\"}";

    /**
     * 响应示例49: 413 Payload Too Large - 请求体过大
     */
    public static final String RESPONSE_413_PAYLOAD_TOO_LARGE = "" +
            "HTTP/1.1 413 Payload Too Large\r\n" +
            "Content-Type: application/json;charset=utf-8\r\n" +
            "Content-Length: 69\r\n" +
            "Retry-After: 60\r\n" +
            "Date: Thu, 19 Mar 2026 08:00:00 GMT\r\n" +
            "Server: wastnet-http/1.0\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            "{\"status\":\"error\",\"message\":\"Payload too large\"}";

    /**
     * 响应示例50: 507 Insufficient Storage - 存储空间不足
     */
    public static final String RESPONSE_507_INSUFFICIENT_STORAGE = "" +
            "HTTP/1.1 507 Insufficient Storage\r\n" +
            "Content-Type: application/json;charset=utf-8\r\n" +
            "Content-Length: 70\r\n" +
            "Date: Thu, 19 Mar 2026 08:00:00 GMT\r\n" +
            "Server: wastnet-http/1.0\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            "{\"status\":\"error\",\"message\":\"Insufficient storage\"}";

    public static void main(String[] args) {
        System.out.println("HTTP Request Examples - Available Samples:");
        System.out.println("===========================================");
        System.out.println("\n=== HTTP Requests (60 examples) ===");
        System.out.println("1. Simple GET Request");
        System.out.println("2. GET with Query Parameters");
        System.out.println("3. POST with JSON Data");
        System.out.println("4. POST with Form Data");
        System.out.println("5. File Upload (Single File)");
        System.out.println("6. File Upload with Form Fields");
        System.out.println("7. PUT Request");
        System.out.println("8. DELETE Request");
        System.out.println("9. Request with Cookie");
        System.out.println("10. Request with Authorization");
        System.out.println("11. Range Request");
        System.out.println("12. Conditional Request (If-Modified-Since)");
        System.out.println("13. Conditional Request (If-None-Match)");
        System.out.println("14. OPTIONS Request (CORS Preflight)");
        System.out.println("15. HEAD Request");
        System.out.println("16. Large File Upload");
        System.out.println("17. Request with Custom Headers");
        System.out.println("18. Chunked Transfer Encoding");
        System.out.println("19. Request with Compression");
        System.out.println("20. PATCH Request");
        System.out.println("21. WebSocket Upgrade Request");
        System.out.println("22. Basic Authentication Request");
        System.out.println("23. POST XML Data Request");
        System.out.println("24. Multiple Files Upload");
        System.out.println("25. HTTP/1.0 Request");
        System.out.println("26. Expect: 100-Continue Request");
        System.out.println("27. Request with Referer");
        System.out.println("28. TRACE Request");
        System.out.println("29. Force Refresh Cache Request");
        System.out.println("30. Request with Detailed User-Agent");
        System.out.println("31. POST Binary Data Request");
        System.out.println("32. Multi-Range Request");
        System.out.println("33. Conditional Request (If-Match)");
        System.out.println("34. Conditional Request (If-Unmodified-Since)");
        System.out.println("35. Conditional Request (If-Range)");
        System.out.println("36. Proxy Request");
        System.out.println("37. Request with Keep-Alive Parameters");
        System.out.println("38. Pagination Request");
        System.out.println("39. WebDAV PROPFIND Request");
        System.out.println("40. Request with DNT (Do Not Track)");
        System.out.println("41. Request with X-Forwarded-For");
        System.out.println("42. Request with Sec-Fetch Headers");
        System.out.println("43. POST YAML Data Request");
        System.out.println("44. Request with Priority");
        System.out.println("45. Request with Content Negotiation");
        System.out.println("46. Digest Authentication Request");
        System.out.println("47. POST CSV Data Request");
        System.out.println("48. GraphQL Request");
        System.out.println("49. Server-Sent Events (SSE) Request");
        System.out.println("50. POST MessagePack Data Request");
        System.out.println("51. API Key Authentication Request");
        System.out.println("52. Request with From Header");
        System.out.println("53. Request with TE Header");
        System.out.println("54. Request with Max-Forwards");
        System.out.println("55. Request with Via Header");
        System.out.println("56. PATCH Merge Request");
        System.out.println("57. Request with Warning Header");
        System.out.println("58. Large Range Request");
        System.out.println("59. Last Bytes Range Request");
        System.out.println("60. POST Form with Array Data");
        System.out.println("\n=== HTTP Responses (50 examples) ===");
        System.out.println("1. 200 OK - Success Response");
        System.out.println("2. 201 Created - Resource Created");
        System.out.println("3. 204 No Content - Success Without Content");
        System.out.println("4. 301 Moved Permanently - Permanent Redirect");
        System.out.println("5. 302 Found - Temporary Redirect");
        System.out.println("6. 304 Not Modified - Cache Hit");
        System.out.println("7. 400 Bad Request - Invalid Request");
        System.out.println("8. 401 Unauthorized - Authentication Required");
        System.out.println("9. 403 Forbidden - Access Denied");
        System.out.println("10. 404 Not Found - Resource Not Found");
        System.out.println("11. 500 Internal Server Error - Server Error");
        System.out.println("12. 503 Service Unavailable - Service Down");
        System.out.println("13. 206 Partial Content - Range Response");
        System.out.println("14. 100 Continue - Continue Request");
        System.out.println("15. Response with Gzip Compression");
        System.out.println("16. Chunked Transfer Encoding Response");
        System.out.println("17. Response with Set-Cookie");
        System.out.println("18. CORS Preflight Response");
        System.out.println("19. WebSocket Upgrade Response");
        System.out.println("20. HTML Page Response");
        System.out.println("21. 429 Too Many Requests - Rate Limited");
        System.out.println("22. 502 Bad Gateway - Gateway Error");
        System.out.println("23. 504 Gateway Timeout - Gateway Timeout");
        System.out.println("24. 409 Conflict - Resource Conflict");
        System.out.println("25. 422 Unprocessable Entity - Semantic Error");
        System.out.println("26. Multi-Range Response");
        System.out.println("27. 405 Method Not Allowed");
        System.out.println("28. 408 Request Timeout");
        System.out.println("29. 410 Gone - Resource Permanently Deleted");
        System.out.println("30. 414 URI Too Long");
        System.out.println("31. 303 See Other - Redirect");
        System.out.println("32. 307 Temporary Redirect");
        System.out.println("33. 308 Permanent Redirect");
        System.out.println("34. 415 Unsupported Media Type");
        System.out.println("35. 416 Range Not Satisfiable");
        System.out.println("36. 417 Expectation Failed");
        System.out.println("37. 426 Upgrade Required");
        System.out.println("38. 428 Precondition Required");
        System.out.println("39. 451 Unavailable For Legal Reasons");
        System.out.println("40. 501 Not Implemented");
        System.out.println("41. 505 HTTP Version Not Supported");
        System.out.println("42. Server-Sent Events (SSE) Response");
        System.out.println("43. Response with Warning Header");
        System.out.println("44. Response with Via Header");
        System.out.println("45. Response with ETag");
        System.out.println("46. Response with Last-Modified");
        System.out.println("47. 205 Reset Content");
        System.out.println("48. 412 Precondition Failed");
        System.out.println("49. 413 Payload Too Large");
        System.out.println("50. 507 Insufficient Storage");
        System.out.println("\nTotal: 60 request examples + 50 response examples = 110 samples");
        System.out.println("\nUse these examples for HTTP protocol testing and development.");
    }
}

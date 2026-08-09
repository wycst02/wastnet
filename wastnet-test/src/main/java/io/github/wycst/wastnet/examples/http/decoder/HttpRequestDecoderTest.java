package io.github.wycst.wastnet.examples.http.decoder;

import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpRequestDecoder;

/**
 * @since 2024-11-17
 * @author wangyc
 */
public class HttpRequestDecoderTest {

    public static void main(String[] args) {
        String source = "POST /sdsdsdsd/%E4%B8%AD%E5%9B%BD%20%E7%9C%8B/sdsd?name=%E4%B8%AD%E5%9B%BD%20&key=123&name=kkk HTTP/1.1\r\n" +
                "Content-Type: application/json\r\n" +
                "X-Token: eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJleHAiOjE3MTk4MDY5NDQsInVzZXJuYW1lIjoieWFuZ2ppbmxpYW4ifQ.tJcMcMUd8kUILVI0IWZkXsa_TpAyxMfEwstqC3ZhSOU\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Postman-Token: fde13918-eb75-4390-bc97-c44fd20b0190\r\n" +
                "User-Agent: PostmanRuntime/7.1.1\r\n" +
                "Accept: */*\r\n" +
                "Host: localhost:8999\r\n" +
                "Accept-Encoding: gzip, deflate\r\n" +
                "Content-Length: 80\r\n" +
                "Connection: keep-alive\r\n" +
                "\r\n" +
                "{\"caseId\":\"090bc9c539000000\",\"domainIds\":[\"domain1\",\"domain2\"],\"dateRange\":\"3h\"}";

//        source ="POST / HTTP/1.1\r\nContent-Length: 0\r\n\r\n";

        //        System.out.println(source);
        byte[] bytes = source.getBytes();

        HttpRequestDecoder httpRequestDecoder = new HttpRequestDecoder();
        httpRequestDecoder.setShallow(true);
        HttpRequest request = null;
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 1000000; ++i) {
            // Test byte by byte
//            for (int j = 0; j < bytes.length; ++j) {
//                httpRequestDecoder.decode(bytes, j, 1);
//            }

            httpRequestDecoder.decode(bytes, 0, bytes.length);

            request = (HttpRequest) httpRequestDecoder.getResult();
            httpRequestDecoder.reset();
        }
        long end = System.currentTimeMillis();
        System.out.println("use " + (end - begin));

        // httpRequestDecoder.decodeRequest(bytes, 0, bytes.length);
        // request = httpRequestDecoder.getDecodedRequest();
        // httpRequestDecoder.reset();

        System.out.println("is bad: " + request.isBad());
        System.out.println(request.getHttpVersion());
        System.out.println(request.getContentType());
        System.out.println(request.getUri());
        System.out.println(request.getRequestUri());
        System.out.println(request.getUriParameter("name"));
        System.out.println("headers: ");
        System.out.println("--------------------------------------------------------------------");
        for (String headerName : request.getHeaderNames()) {
            System.out.println(headerName + ": " + request.getHeader(headerName));
        }
    }

}

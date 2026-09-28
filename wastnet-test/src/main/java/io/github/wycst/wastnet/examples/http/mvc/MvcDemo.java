package io.github.wycst.wastnet.examples.http.mvc;
import io.github.wycst.wast.common.reflect.GenericParameterizedType;
import io.github.wycst.wast.json.JSON;
import io.github.wycst.wastnet.http.HTTPServer;
import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.http.annotation.AnnotationRouterHandler;
import io.github.wycst.wastnet.http.annotation.ConverterConfig;
import io.github.wycst.wastnet.http.annotation.HttpMessageConverter;
import io.github.wycst.wastnet.examples.http.mvc.view.FreeMarkerViewResolver;
import io.github.wycst.wastnet.socket.tcp.NioConfig;

import java.io.InputStream;
import java.lang.reflect.Type;

/**
 * 注解路由 + 轻量 DI + HttpMessageConverter SPI 综合演示.
 * <p>
 * 启动后访问：
 * <ul>
 *   <li><a href="http://localhost:8080/api/user/list">/api/user/list</a></li>
 *   <li><a href="http://localhost:8080/api/user/get?id=42">/api/user/get?id=42</a></li>
 *   <li><a href="http://localhost:8080/api/user/save?name=foo">/api/user/save?name=foo</a></li>
 *   <li><a href="http://localhost:8080/hello">/hello</a>（返回纯文本 "hello world"）</li>
 *   <li><a href="http://localhost:8080/json">/json</a>（返回 {"message":"hello world"}）</li>
 *   <li><a href="http://localhost:8080/demo/view/user">/demo/view/user</a>（FreeMarker 视图渲染）</li>
 *   <li><a href="http://localhost:8080/demo/view/hello">/demo/view/hello</a>（FreeMarker 视图渲染, 空模型）</li>
 *   <li>WebSocket: ws://localhost:8080/ws/chat</li>
 * </ul>
 *
 * @author wangyc
 */
public class MvcDemo {

    public static void main(String[] args) throws Exception {
        // 开启 H2 连接监控，供 /h2monitor/connections 端点采集数据
        System.setProperty("wastnet.h2.monitor", "true");
        // 1. 创建注解路由器，注入 HttpMessageConverter SPI（返回值自动 JSON 序列化 + @RequestBody 反序列化）
        AnnotationRouterHandler annotationRouterHandler = new AnnotationRouterHandler()
                .messageConverter(new HttpMessageConverter() {
                    @Override
                    public void write(Object value, ConverterConfig config, HttpResponse response) throws Exception {
                        response.contentType(config.getResponseContentType());
                        if (config.isPretty()) {
                            response.body(JSON.toPrettifyJsonString(value));
                        } else {
                            response.body(JSON.toJsonBytes(value));
                        }
                    }

                    @Override
                    public Object read(HttpRequest request, ConverterConfig config, Type type) throws Exception {
                        GenericParameterizedType<?> genericParameterizedType = GenericParameterizedType.of(type);
                        if (request.isStream()) {
                            InputStream is = request.bodyStream();
                            return JSON.read(is, genericParameterizedType);
                        } else {
                            byte[] data = request.getBodyData();
                            if (data == null || data.length == 0) return null;
                            return JSON.parse(data, genericParameterizedType);
                        }
                    }
                })
                // 视图解析器：非 @ResponseBody 返回值经 FreeMarker 渲染为 HTML
                .addViewResolver(new FreeMarkerViewResolver())
                // 2. 配置属性（用于 @Value 注入）
                .property("app.prefix", "Member-")
                // 3. 扫描包
                .scanPackages("io.github.wycst.wastnet.examples.http.mvc")
                .configFiles("demo.properties");

        // demo 用于性能压测， 禁用拦截器（拦截器中存在 System.out 输出）
        annotationRouterHandler.interceptorsDisabled(true);

        // 4. 启动 HTTP 服务器
        int port = 8080;
        
        NioConfig nioConfig = new NioConfig();
        nioConfig.testMode();
        
        HTTPServer server = HTTPServer.of(port, nioConfig)
        .requestHandler(annotationRouterHandler)
        .startupBannerEnabled(true); //（关闭默认 banner，改为打印可用入口）
        
        // 启用 SSL，访问应使用 https / wss
        boolean ssl = System.getProperty("mvc.demo.ssl", "true").equals("true");
        if(ssl) {
            server.pemSSL("cert/cert.pem", "cert/server.pem")
                  .h2();
        }
        server.start();
        printEndpoints(port, ssl);
    }

    private static void printEndpoints(int port, boolean ssl) {
        String base = (ssl ? "https" : "http") + "://localhost:" + port;
        String ws = (ssl ? "wss" : "ws") + "://localhost:" + port;
        System.out.println("Server started, available endpoints:");
        System.out.println("  " + base + "/api/user/list");
        System.out.println("  " + base + "/api/user/get?id=42");
        System.out.println("  " + base + "/api/user/save?name=foo");
        System.out.println("  " + base + "/hello");
        System.out.println("  " + base + "/json");
        System.out.println("  " + base + "/demo/view/user   (FreeMarker 视图渲染)");
        System.out.println("  " + base + "/demo/view/hello  (FreeMarker 视图渲染, 空模型)");
        System.out.println("  " + base + "/h2monitor/connections  (need -Dwastnet.h2.monitor=true)");
        System.out.println("  " + base + "/hdr-single   (header X-Client)");
        System.out.println("  " + base + "/hdr-multi    (header X-Tags, multi-value)");
        System.out.println("  " + base + "/hdr-default  (header X-Env, default=dev)");
        System.out.println("  " + base + "/hdr-required (header X-Token, required)");
        System.out.println("  " + base + "/hdr-int      (header X-Max, int, default=0)");
        System.out.println("  " + base + "/sse-clock           (SSE: 每秒推送，curl -N 消费)");
        System.out.println("  " + base + "/sse-stream          (SSE: 持续推送到超时断开)");
        System.out.println("  " + base + "/sse-full            (SSE: 全字段事件)");
        System.out.println("  " + base + "/sse/room/${roomId}  (SSE: 带路径参数)");
        System.out.println("  " + base + "/sse/user?id=123     (SSE: 带请求参数)");
        System.out.println("  WebSocket: " + ws + "/ws/chat");
    }
}

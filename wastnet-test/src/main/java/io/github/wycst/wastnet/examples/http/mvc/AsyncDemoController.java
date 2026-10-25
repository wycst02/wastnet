package io.github.wycst.wastnet.examples.http.mvc;

import io.github.wycst.wastnet.http.*;
import io.github.wycst.wastnet.http.annotation.*;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 异步端点示例：@Endpoint(async = true) 的业务方法提交到 ctx.runAsync 执行，
 * 请求线程立即返回，不再被慢业务阻塞。
 * <p>
 * 压测对比（h2load 同时打 /asyncDelay 和 /asyncHello）：
 * /asyncHello 吞吐应与无 /asyncDelay 时基本一致（快慢分离生效）；
 * 与同步版 /delay（TestController，sleep 15ms）对比可看出隔离收益。
 */
@RestController
public class AsyncDemoController {

    /** 慢端点（异步）：sleep 期间不占用请求线程。 */
    @Endpoint(value = "/asyncDelay", async = true, responseType = ContentType.TEXT)
    public String asyncDelay(@RequestParam(value = "ms", defaultValue = "15") int ms) throws InterruptedException {
        Thread.sleep(ms);
        return "ok";
    }


    /** asyncTimer 期间不占用请求线程。 */
    @Endpoint(value = "/asyncTimer", responseType = ContentType.TEXT)
    public void asyncTimer(@RequestParam(value = "ms", defaultValue = "15") int ms, final HttpRequest request,  final HttpResponse response) throws InterruptedException {
       ChannelContext ctx = ((HttpInternalRequest) request).ctx();
       response.setAutoCommit(false);
       ctx.schedule(() -> {
           response.body("ok");
           try {
             response.commit();
           } catch (IOException ignored) {
           }
       }, ms);
    }

    /** 快端点（同步）：验证慢端点不会拖慢它。 */
    @Endpoint(value = "/asyncHello", responseType = ContentType.TEXT)
    public String asyncHello() {
        return "hello";
    }

    /** 异步 + 参数绑定（通用路径）：参数仍在请求线程装配。 */
    @Endpoint(value = "/asyncEcho", async = true, responseType = ContentType.TEXT)
    public String asyncEcho(@RequestParam("msg") String msg, @RequestBody Map<String, Object> body) {
        return msg + ":" + body;
    }

    /** 异步 + (HttpRequest, HttpResponse) 快路径。 */
    @Endpoint(value = "/asyncRawEcho", async = true, responseType = ContentType.TEXT)
    public void asyncRawEcho(HttpRequest request, HttpResponse response) throws Exception {
        response.contentType("text/plain;charset=utf-8").body(request.getBodyData());
    }

    /** 异步端点抛异常：返回 500（响应未提交时）。 */
    @Endpoint(value = "/asyncError", async = true, responseType = ContentType.TEXT)
    public String asyncError() {
        throw new IllegalStateException("boom");
    }

    /** 异步处理上传。 */
    @Endpoint(value = "/asyncUpload", async = true, responseType = ContentType.TEXT)
    public String asyncUpload(@RequestParam("file") MultipartField file) throws IOException {
        System.out.println("------- " + file.getFileName());
        return "success";
    }
}

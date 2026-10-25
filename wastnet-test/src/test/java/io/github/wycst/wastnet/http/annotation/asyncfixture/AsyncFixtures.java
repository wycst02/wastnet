package io.github.wycst.wastnet.http.annotation.asyncfixture;

import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.http.annotation.Controller;
import io.github.wycst.wastnet.http.annotation.Endpoint;

public class AsyncFixtures {

    @Controller("/async")
    public static class AsyncDelayController {
        @Endpoint(value = "/delay", async = true)
        public void delay(HttpResponse resp) throws Exception {
            Thread.sleep(50);
            resp.contentType("text/plain;charset=utf-8").body("ok".getBytes());
        }
    }

    @Controller("/async")
    public static class AsyncErrorController {
        @Endpoint(value = "/err", async = true)
        public void err(HttpResponse resp) {
            throw new RuntimeException("boom");
        }
    }

    @Controller("/sync")
    public static class SyncHelloController {
        @Endpoint("/hello")
        public void hello(HttpResponse resp) {
            resp.contentType("text/plain;charset=utf-8").body("hello".getBytes());
        }
    }
}

package io.github.wycst.wastnet.examples.http.monitor;

import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.http.annotation.Controller;
import io.github.wycst.wastnet.http.annotation.Endpoint;

/**
 * Benchmark endpoint for h2load testing.
 *
 * @author wangyc
 */
@Controller
public class HelloController {

    @Endpoint("/hello")
    public void hello(HttpRequest request, HttpResponse response) {
        response.contentLength(2).body("ok");
    }
}

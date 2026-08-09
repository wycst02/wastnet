package io.github.wycst.wastnet.examples.http.mvc;

import io.github.wycst.wastnet.http.annotation.ContentType;
import io.github.wycst.wastnet.http.annotation.Endpoint;
import io.github.wycst.wastnet.http.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 演示控制器：/hello 返回纯文本（responseType=TEXT）；/json 返回 JSON（默认 responseType=JSON）.
 */
@RestController
public class TestController {

    @Endpoint(value = "/hello", responseType = ContentType.TEXT)
    public String hello() {
        return "hello world";
    }

    @Endpoint("/json")
    public Object json() {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("message", "hello world");
        return map;
    }
}

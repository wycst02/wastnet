package io.github.wycst.wastnet.examples.http.mvc;

import io.github.wycst.wastnet.http.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 演示控制器：/hello 返回纯文本（responseType=TEXT）；/json 返回 JSON（默认 responseType=JSON）.
 */
@RestController
public class TestController {

    @Value("${msg}")
    private String msg;

    @Endpoint(value = "/hello", responseType = ContentType.TEXT)
    public String hello(String name) {
        return msg;
    }

    @Endpoint("/json")
    public Object json() {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("message", msg);
        return map;
    }

    /**
     * GET /rp-int        -> 缺省 page，应绑定为 0
     * GET /rp-int?page=2 -> 返回 {"page":2}
     */
    @Endpoint("/rp-int")
    public Object rpInt(@RequestParam(value = "page", required = false) int page) {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("page", page);
        return map;
    }

    /**
     * GET /rp-int-box        -> 缺省 page，应绑定为 null
     */
    @Endpoint("/rp-int-box")
    public Object rpIntBox(@RequestParam(value = "page", required = false) Integer page) {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("page", page);
        return map;
    }
}

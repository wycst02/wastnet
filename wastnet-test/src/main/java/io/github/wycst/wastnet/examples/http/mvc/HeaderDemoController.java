package io.github.wycst.wastnet.examples.http.mvc;

import io.github.wycst.wastnet.http.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 请求头（@RequestHeader）绑定用法演示：
 *  - /hdr-single    单值头绑定
 *  - /hdr-multi     多值头（String[]）
 *  - /hdr-default   非必填 + defaultValue
 *  - /hdr-required  必填头缺失时抛异常
 *  - /hdr-int       头值类型转换（int）+ defaultValue
 */
@RestController
public class HeaderDemoController {

    // 单值头绑定（请求头 X-Client）
    @Endpoint("/hdr-single")
    public Object single(@RequestHeader("X-Client") String client) {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("client", client);
        return map;
    }

    // 多值头绑定（请求头 X-Tags，可多次出现）
    @Endpoint("/hdr-multi")
    public Object multi(@RequestHeader("X-Tags") String[] tags) {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("tags", tags);
        return map;
    }

    // 非必填 + 默认值（不传 X-Env 时绑定 "dev"）
    @Endpoint("/hdr-default")
    public Object withDefault(@RequestHeader(value = "X-Env", required = false, defaultValue = "dev") String env) {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("env", env);
        return map;
    }

    // 必填头（不传 X-Token 抛 Missing required header）
    @Endpoint("/hdr-required")
    public Object required(@RequestHeader("X-Token") String token) {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("token", token);
        return map;
    }

    // 类型转换 + 默认值（X-Max 转 int，缺失为 0）
    @Endpoint("/hdr-int")
    public Object asInt(@RequestHeader(value = "X-Max", required = false, defaultValue = "0") int max) {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("max", max);
        return map;
    }
}

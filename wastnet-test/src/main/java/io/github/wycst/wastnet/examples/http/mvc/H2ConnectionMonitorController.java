package io.github.wycst.wastnet.examples.http.mvc;

import io.github.wycst.wastnet.http.annotation.Controller;
import io.github.wycst.wastnet.http.annotation.Endpoint;
import io.github.wycst.wastnet.http.annotation.ResponseBody;
import io.github.wycst.wastnet.http.h2.H2Monitor;

import java.util.Map;

/**
 * 监控 H2 连接情况：连接数、活跃流数、收发窗口等。
 * <p>
 * 需在启动时开启监控：{@code -Dwastnet.h2.monitor=true}，否则 global() 仅返回 enabled=false。
 *
 * @author wangyc
 */
@Controller("/h2monitor")
public class H2ConnectionMonitorController {

    @ResponseBody
    @Endpoint("/connections")
    public Map<String, Object> connections() {
        return H2Monitor.global();
    }

    /**
     * 重置进程级累计计数与最大耗时（压测前调用）。
     */
    @ResponseBody
    @Endpoint("/reset")
    public String reset() {
        H2Monitor.reset();
        return "ok";
    }
}

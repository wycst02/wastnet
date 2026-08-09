package io.github.wycst.wastnet.examples.http.monitor;

import io.github.wycst.wastnet.http.annotation.Controller;
import io.github.wycst.wastnet.http.annotation.Endpoint;
import io.github.wycst.wastnet.http.annotation.ResponseBody;
import io.github.wycst.wastnet.http.h2.H2Monitor;

import java.util.Map;

/**
 * REST endpoint to expose {@link H2Monitor} statistics.
 *
 * @author wangyc
 */
@Controller("/h2monitor")
public class H2MonitorController {

    @ResponseBody
    @Endpoint("/global")
    public Map<String, Object> global() {
        return H2Monitor.global();
    }

}

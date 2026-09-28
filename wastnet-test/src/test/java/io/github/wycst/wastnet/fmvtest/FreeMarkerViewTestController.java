package io.github.wycst.wastnet.fmvtest;

import io.github.wycst.wastnet.examples.http.mvc.view.ModelAndView;
import io.github.wycst.wastnet.http.annotation.Controller;
import io.github.wycst.wastnet.http.annotation.Endpoint;

/**
 * 测试用控制器：非 {@code @ResponseBody} 返回值交 FreeMarkerViewResolver 渲染。
 */
@Controller("/fmv")
public class FreeMarkerViewTestController {

    @Endpoint("/user")
    public ModelAndView user() {
        return new ModelAndView("user.ftl")
                .add("name", "wastnet")
                .add("roles", new String[]{"admin", "dev"});
    }
}

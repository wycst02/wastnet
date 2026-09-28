package io.github.wycst.wastnet.examples.http.mvc.view;

import io.github.wycst.wastnet.http.annotation.Controller;
import io.github.wycst.wastnet.http.annotation.Endpoint;

/**
 * 演示非 {@code @ResponseBody} 返回值经 {@link FreeMarkerViewResolver} 渲染为 HTML。
 */
@Controller("/demo/view")
public class ViewDemoController {

    /** 返回 ModelAndView：视图名 + 模型，交给 FreeMarkerViewResolver 渲染。 */
    @Endpoint("/user")
    public ModelAndView user() {
        return new ModelAndView("user.ftl")
                .add("name", "wastnet")
                .add("roles", new String[]{"admin", "dev"});
    }

    /** 返回视图名 String（模型为空），同样由 FreeMarkerViewResolver 渲染。 */
    @Endpoint("/hello")
    public ModelAndView hello() {
        return new ModelAndView("user.ftl");
    }
}

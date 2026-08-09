package io.github.wycst.wastnet.examples.http.mvc;

import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.http.annotation.Controller;
import io.github.wycst.wastnet.http.annotation.Endpoint;
import io.github.wycst.wastnet.http.annotation.Inject;
import io.github.wycst.wastnet.http.annotation.RequestBody;
import io.github.wycst.wastnet.http.annotation.ResponseBody;

/**
 * REST 控制器 — 演示 {@code @Controller}、{@code @Endpoint}、{@code @Inject}.
 * <p>
 * 当 {@code HttpMessageConverter} 配置后，有返回值的方法会自动序列化。
 *
 * @author wangyc
 */
@Controller("/api/user")
public class UserController {

    /** 字段注入（容器自动注入 UserService 实例） */
    @Inject
    private UserService userService;

    @ResponseBody
    @Endpoint("/list")
    public String list() {
        return userService.listUsers();
    }

    @ResponseBody
    @Endpoint("/get")
    public String get(HttpRequest request) {
        String idStr = request.getParameter("id");
        int id = idStr != null ? Integer.parseInt(idStr) : 0;
        return userService.getUserName(id);
    }

    @Endpoint("/save")
    public void save(HttpRequest request, HttpResponse response) throws Exception {
        response.contentType("text/plain;charset=utf-8")
                .body("saved: " + request.getParameter("name"));
    }

    @ResponseBody
    @Endpoint("/create")
    public String create(@RequestBody CreateUserReq req) {
        return userService.getUserName(req.id);
    }
}

package io.github.wycst.wastnet.examples.http.mvc;

import io.github.wycst.wastnet.http.annotation.Endpoint;
import io.github.wycst.wastnet.http.annotation.RestController;
import io.github.wycst.wastnet.http.annotation.WithInterceptor;

/**
 * 演示 @WithInterceptor 的类级与方法级绑定。
 * 类级作用于该类下所有端点，方法级在类级基础上追加。
 */
@RestController("/admin")
@WithInterceptor("admin")
public class AdminController {

    @Endpoint("/users")
    public String users() {
        return "admin user list";
    }

    @Endpoint("/stat")
    public String stat() {
        return "admin stat";
    }
}

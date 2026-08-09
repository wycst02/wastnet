package io.github.wycst.wastnet.examples.http.mvc;

import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.http.annotation.Interceptor;
import io.github.wycst.wastnet.http.annotation.InterceptorType;
import io.github.wycst.wastnet.http.handler.RouterInterceptor;

/**
 * 端点级拦截器：type = ENDPOINT 表示不进入全局链，
 * 只有被 @WithInterceptor("admin") 引用的类或方法才会执行。
 */
@Interceptor(value = "admin", order = 1, type = InterceptorType.ENDPOINT)
public class AdminAuthInterceptor implements RouterInterceptor {

    @Override
    public boolean beforeHandle(String path, HttpRequest request, HttpResponse response) throws Throwable {
        // 演示用：真实项目应校验 token 中的角色信息
        String role = request.getHeader("X-Role");
        if (!"admin".equals(role)) {
            response.status(403).body("Forbidden: admin role required");
            return false;
        }
        return true;
    }
}

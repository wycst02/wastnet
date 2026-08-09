package io.github.wycst.wastnet.examples.http.mvc;

import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.http.annotation.Interceptor;
import io.github.wycst.wastnet.http.handler.RouterInterceptor;

/**
 * 注解拦截器：实现 RouterInterceptor 并标注 @Interceptor，
 * scanPackages 后自动注册到父类拦截器链（order 越小越先执行）。
 */
@Interceptor(order = 1)
public class AuthLogInterceptor implements RouterInterceptor {

    @Override
    public boolean beforeHandle(String path, HttpRequest request, HttpResponse response) throws Throwable {
        // 记录请求日志（演示用途，生产环境应接入日志系统）
        System.out.println("[" + request.getMethod() + "] " + path);
        return true;
    }
}

package io.github.wycst.wastnet.examples.http.mvc;

import io.github.wycst.wastnet.http.HTTPServer;
import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.http.annotation.AnnotationRouterHandler;
import io.github.wycst.wastnet.http.handler.RouterInterceptor;

/**
 * 演示路由级拦截器的两类用法：
 * 1) 前置路由拦截器（PRE_ROUTE，默认）：全局无条件执行，路由分发前按 order 升序执行，
 *    任一返回 false 即短路。可手动 router.interceptor(...) 注册，或标注 @Interceptor 自动注册。
 * 2) 端点拦截器（ENDPOINT）：不进全局链，只对 @WithInterceptor("名字") 引用的类/方法生效，
 *    见 AdminAuthInterceptor + AdminController。
 *
 * 访问 /hello 需带 Authorization 头；不带则返回 401。访问 /login 放行。
 * 访问 /admin/users 还需 X-Role: admin，否则 403。
 */
public class InterceptorDemo {

    public static void main(String[] args) throws Exception {
        AnnotationRouterHandler router = new AnnotationRouterHandler();

        // 手动注册登录鉴权拦截器（公开路径放行，其余需 Authorization 头）
        router.interceptor(new RouterInterceptor() {
            @Override
            public boolean beforeHandle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                if ("/login".equals(path) || path.startsWith("/static")) {
                    return true;   // 公开路径放行
                }
                if (request.getHeader("Authorization") == null) {
                    response.status(401).body("Unauthorized");
                    return false;  // 短路，不进入 controller
                }
                return true;
            }
        });

        // 扫描包：PRE_ROUTE 的 AuthLogInterceptor 自动加入全局链；
        // ENDPOINT 的 AdminAuthInterceptor 只绑定到 @WithInterceptor("admin") 的端点
        router.scanPackages("io.github.wycst.wastnet.examples.http.mvc");

        HTTPServer.of(8081)
                .requestHandler(router)
                .start();

        System.out.println("Server started on http://localhost:8081");
        System.out.println("Test:  curl http://localhost:8081/hello            -> 401");
        System.out.println("       curl -H 'Authorization: Bearer x' http://localhost:8081/hello -> 200");
        System.out.println("       curl -H 'Authorization: Bearer x' http://localhost:8081/admin/users -> 403");
        System.out.println("       curl -H 'Authorization: Bearer x' -H 'X-Role: admin' http://localhost:8081/admin/users -> 200");
    }
}

package io.github.wycst.wastnet.routerfixtures;

import io.github.wycst.wastnet.http.*;
import io.github.wycst.wastnet.http.annotation.*;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.math.BigDecimal;
import java.util.List;

/**
 * Fixture controllers/components for {@code AnnotationRouterHandler} tests.
 * Kept in an isolated package (NOT under {@code http.annotation}) so the existing
 * full-package scan in {@code AnnotationPackageTest} is not disturbed.
 * Nested types are discovered by {@link PackageScanner}.
 */
public class RouterFixtures {

    // ============ real controllers scanned for runtime ============

    @Controller("/pv")
    public static class PathVarController {
        @Endpoint("/items/${id}")
        public void item(@PathParam("id") int id) {
        }

        @Endpoint("/user/{uid:\\d+}")
        public void user(@PathParam("uid") long uid) {
        }

        @Endpoint("/name/{name}")
        public void name(@PathParam("name") String name) {
        }
    }

    @Controller("/rp")
    public static class RequestParamController {
        @Endpoint("/search")
        public void search(@RequestParam("q") String q,
                           @RequestParam(value = "tags", required = false) String[] tags,
                           @RequestParam(value = "names", required = false) List<String> names,
                           @RequestParam(value = "opt", required = false, defaultValue = "x") String opt,
                           @RequestParam(value = "e", required = false, defaultValue = "") String e,
                           @RequestParam(value = "extra", required = false) List<String> extra) {
        }
    }

    @Controller("/rp2")
    public static class FileParamController {
        @Endpoint("/upload")
        public void upload(@RequestParam("f") MultipartField f,
                           @RequestParam("fs") MultipartField[] fs) {
        }
    }

    // ============ request-param conversion coverage (public API) ============
    // Endpoints write the bound value straight to the response so the full scan -> bind -> invoke
    // path can be exercised through a real HTTP server (no direct internal calls). Covers #5
    // (optional primitive binds to 0) and #6 (BigDecimal / enum conversion), plus defaultValue
    // and required-missing (-> 400).

    public enum Status { ACTIVE, INACTIVE }

    @Controller("/conv")
    public static class ConvertController {
        @Endpoint("/int")
        public void intParam(@RequestParam(value = "v", required = false) int v, HttpResponse resp) {
            resp.contentType("text/plain;charset=utf-8").body(("int=" + v).getBytes());
        }

        @Endpoint("/intbox")
        public void intBox(@RequestParam(value = "v", required = false) Integer v, HttpResponse resp) {
            resp.contentType("text/plain;charset=utf-8").body(("intbox=" + v).getBytes());
        }

        @Endpoint("/bd")
        public void bigDecimal(@RequestParam(value = "v", required = false) BigDecimal v, HttpResponse resp) {
            resp.contentType("text/plain;charset=utf-8").body(("bd=" + v).getBytes());
        }

        @Endpoint("/enum")
        public void status(@RequestParam(value = "v", required = false) Status v, HttpResponse resp) {
            resp.contentType("text/plain;charset=utf-8").body(("enum=" + v).getBytes());
        }

        @Endpoint("/def")
        public void withDefault(@RequestParam(value = "v", required = false, defaultValue = "9") int v, HttpResponse resp) {
            resp.contentType("text/plain;charset=utf-8").body(("def=" + v).getBytes());
        }

        @Endpoint("/req")
        public void required(@RequestParam("v") String v, HttpResponse resp) {
            resp.contentType("text/plain;charset=utf-8").body(("req=" + v).getBytes());
        }

        @Endpoint("/reqmulti")
        public void reqMulti(@RequestParam(value = "t", required = true) String[] t, HttpResponse resp) {
            resp.contentType("text/plain;charset=utf-8").body(("reqmulti=" + java.util.Arrays.toString(t)).getBytes());
        }
    }

    @Controller("/sse-throw")
    public static class SseThrowController {
        @Sse("/stream")
        public void stream(SseEmitter e) {
            throw new RuntimeException("boom");
        }
    }

    @Controller("/gb")
    public static class GeneralBodyNoConvController {
        @Endpoint("/up")
        public void up(@RequestBody String body) {
        }
    }

    @Controller("/hdr")
    public static class HeaderParamController {
        @Endpoint("/req")
        public void req(@RequestHeader("X-Req") String x) {
        }

        @Endpoint("/opt")
        public void opt(@RequestHeader(value = "X-Opt", required = false) String x) {
        }

        @Endpoint("/missing")
        public void missing(@RequestHeader("X-Miss") String x) {
        }

        @Endpoint("/multi")
        public void multi(@RequestHeader(value = "X-Multi", required = false) String[] x) {
        }
    }

    @Controller("/pp")
    public static class PrimitiveParamController {
        @Endpoint("/p")
        public void p(int x) {
        }

        @Endpoint("/ps")
        public void ps(String s) {
        }
    }

    // ============ scan-time scenario fixtures ============

    @Controller("/pvm")
    public static class PathVarMismatchController {
        @Endpoint("/x/${id}")
        public void x(@PathParam("other") int o) {
        }
    }

    @Component
    public static class MultiInjectCtorComponent {
        @MyInject
        public MultiInjectCtorComponent() {
        }

        @MyInject
        public MultiInjectCtorComponent(String x) {
        }
    }

    @Configuration
    public static class FailingConfig {
        public FailingConfig() {
            throw new RuntimeException("ctor fail");
        }
    }

    @Configuration
    public static class NamedInjectConfig {
        @Bean("depBean")
        public String depBean() {
            return "DEP";
        }

        @Bean
        public String combined(@Inject("depBean") String dep) {
            return dep + "!";
        }
    }

    // ============ reflection helper fixtures ============

    public static class TypeHolder {
        public void arrayMethod(String[] p) {
        }

        public void listMethod(List<String> p) {
        }

        public void rawListMethod(List p) {
        }

        public void stringMethod(String p) {
        }
    }

    public static class AttrHolder {
        public void params(@RequestParam("name") String x, @NoValue String y) {
        }
    }

    @Retention(RetentionPolicy.RUNTIME)
    public @interface NoValue {
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.CONSTRUCTOR)
    public @interface MyInject {
        String value() default "";
    }

    // Third-party @Bean-equivalent annotation; bridged via handler.beanBy(MyBean.class)
    // and exercised end-to-end through the public scanPackages/scan entry point.
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface MyBean {
        String value() default "";
    }

    @Configuration
    public static class BeanByConfig {
        @MyBean
        public String produce() {
            return "bridged";
        }

        @Bean
        public Integer realBean() {
            return 7;
        }
    }

    @Configuration
    public static class BeanByDefaultConfig {
        @Bean
        public String def() {
            return "default-bean";
        }
    }

    // ============ third-party bridge annotations exercising DefaultAnnotationResolver.annotationValue ============
    // Unlike MyBean/MyInject (which the test resolver special-cases and bypasses annotationValue),
    // these are NOT special-cased, so resolution falls through to the reflection-based annotationValue().

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface ThirdBean {
        String value() default "";
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    public @interface ThirdInject {
        String value() default "";
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    public @interface ThirdValue {
        String value() default "";
    }

    // No value() method: exercises the NoSuchMethodException -> "" branch of annotationValue().
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    public @interface NoValueMethod {
    }

    public static class NoValueDep {
    }

    @Configuration
    public static class ThirdPartyBridgeConfig {
        @ThirdBean("namedBean")
        public String namedBean() {
            return "bridged-third";
        }

        @ThirdBean("uniqueDep")
        public NoValueDep uniqueDep() {
            return new NoValueDep();
        }
    }

    @Component
    public static class ThirdInjectHolder {
        @ThirdInject("namedBean")
        private String dep;

        public String getDep() {
            return dep;
        }
    }

    @Component
    public static class ThirdValueHolder {
        @ThirdValue("lit-value")
        private String v;

        public String getV() {
            return v;
        }
    }

    // Field carries an inject annotation with no value() method -> annotationValue returns "" -> by-type lookup.
    @Component
    public static class NoValueHolder {
        @NoValueMethod
        private NoValueDep nv;

        public NoValueDep getNv() {
            return nv;
        }
    }
}

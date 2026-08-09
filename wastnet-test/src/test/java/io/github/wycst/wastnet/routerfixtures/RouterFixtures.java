package io.github.wycst.wastnet.routerfixtures;

import io.github.wycst.wastnet.http.*;
import io.github.wycst.wastnet.http.annotation.*;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;

/**
 * Fixture controllers/components for {@code AnnotationRouterHandler} coverage tests.
 * Kept in an isolated package (NOT under {@code http.annotation}) so the existing
 * full-package scan in {@code AnnotationPackageCoverageTest} is not disturbed.
 * Nested types are discovered by {@link io.github.wycst.wastnet.http.annotation.PackageScanner}.
 */
public class RouterFixtures {

    // ============ real controllers scanned for runtime coverage ============

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
}

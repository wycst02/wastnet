package io.github.wycst.wastnet.vrtest;

import io.github.wycst.wastnet.http.*;
import io.github.wycst.wastnet.http.annotation.*;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fixtures for {@code AnnotationRouterHandler} view-resolver / converter result handling.
 * Kept in an isolated package (scanned only by the dedicated test) so no existing
 * full-package scan is disturbed.
 */
public class ViewResultFixtures {

    /** Non-{@code @ResponseBody} return type claimed by {@link RenderViewResolver}. */
    public static class RenderView {
        public final String name;

        public RenderView(String name) {
            this.name = name;
        }
    }

    /** Resolver that renders {@link RenderView} to the response. */
    public static class RenderViewResolver implements ViewResolver {
        @Override
        public boolean supports(Class<?> returnType) {
            return RenderView.class.isAssignableFrom(returnType);
        }

        @Override
        public void render(Object returnValue, HttpRequest request, HttpResponse response) throws Exception {
            RenderView rv = (RenderView) returnValue;
            response.contentType("text/plain;charset=utf-8").body(("view:" + rv.name).getBytes(StandardCharsets.UTF_8));
        }
    }

    @Controller("/vr")
    public static class ViewResultController {
        // view resolver branch of writeResult: resolveViewResolver pre-selects RenderViewResolver
        @Endpoint("/render")
        public RenderView render() {
            return new RenderView("ok");
        }

        // handleDefaultResult: File branch (streamed octet-stream)
        @Endpoint("/file")
        public File file() throws Exception {
            File f = File.createTempFile("wastnet-vr", ".txt");
            Files.write(f.toPath(), "file-body".getBytes(StandardCharsets.UTF_8));
            f.deleteOnExit();
            return f;
        }

        // handleDefaultResult: byte[] branch
        @Endpoint("/bytes")
        public byte[] bytes() {
            return "bytes-body".getBytes(StandardCharsets.UTF_8);
        }

        // handleDefaultResult: InputStream branch (chunked)
        @Endpoint("/stream")
        public InputStream stream() {
            return new ByteArrayInputStream("stream-body".getBytes(StandardCharsets.UTF_8));
        }

        // handleDefaultResult: String fallback (String.valueOf)
        @Endpoint("/text")
        public String text() {
            return "text-body";
        }

        // writeResult: @ResponseBody branch -> converter.write
        @ResponseBody
        @Endpoint("/json")
        public Map<String, Object> json() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("k", "v");
            return m;
        }

        // @RequestBody -> AnnotationRouterHandler line 1019 assertBodyAssignable (non-null branch)
        @Endpoint("/body")
        public void body(@RequestBody String body, HttpResponse resp) throws Exception {
            resp.contentType("text/plain;charset=utf-8").body(("echo:" + body).getBytes(StandardCharsets.UTF_8));
        }
    }
}

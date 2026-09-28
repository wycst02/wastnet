package io.github.wycst.wastnet.http.annotation;

import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;

/**
 * Built-in, dependency-free converter for {@link ContentType#TEXT}.
 * <p>
 * Pre-registered by {@link AnnotationRouterHandler} so a {@code @ResponseBody}
 * endpoint that produces {@code text/plain} works out of the box. It may be
 * overridden by calling {@link AnnotationRouterHandler#messageConverter(ContentType, HttpMessageConverter)}
 * with {@link ContentType#TEXT}.
 *
 * @author wangyc
 */
final class TextMessageConverter implements HttpMessageConverter {

    // Serialize the value into a plain-text response body.
    @Override
    public void write(Object value, ConverterConfig config, HttpResponse response) throws Exception {
        String text = value == null ? "" : String.valueOf(value);
        response.contentType(config.getResponseContentType()).body(text);
    }

    // Resolve the request body as text (UTF-8); streamed bodies go through readString().
    @Override
    public Object read(HttpRequest request, ConverterConfig config, Type type) throws Exception {
        String text = request.isStream()
                ? readString(request.bodyStream())
                : new String(request.getBodyData(), StandardCharsets.UTF_8);
        return text.isEmpty() ? null : text;
    }

    // Read the streamed body as a UTF-8 string, decoding straight to chars to avoid an intermediate byte[] copy.
    private static String readString(InputStream in) throws IOException {
        InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(8192);
        char[] cbuf = new char[8192];
        int n;
        while ((n = reader.read(cbuf)) != -1) {
            sb.append(cbuf, 0, n);
        }
        return sb.toString();
    }
}

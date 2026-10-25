package io.github.wycst.wastnet.http.annotation;

import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
public final class TextMessageConverter implements HttpMessageConverter {

    /**
     * Serializes the given value into a plain-text response body.
     *
     * @param value    the value to write; a {@code byte[]} is written as-is, any other type
     *                 is converted via {@link String#valueOf(Object)} (empty string when {@code null})
     * @param config   the converter configuration providing the response content type
     * @param response the HTTP response to write the body to
     * @throws Exception if writing the body fails
     */
    @Override
    public void write(Object value, ConverterConfig config, HttpResponse response) throws Exception {
        response.contentType(config.getResponseContentType());
        if(value instanceof byte[]) {
            response.body((byte[]) value);
        } else {
            response.body(value == null ? "" : String.valueOf(value));
        }
    }

    /**
     * Resolves the request body for a {@code text/plain} endpoint.
     *
     * <p>In stream mode {@link InputStream} returns the live body stream and {@code String}
     * is read through the streaming path; {@code byte[]} (and other types) return {@code null}
     * to avoid buffering the entire body. In non-stream mode the body is resolved from
     * {@link HttpRequest#getBodyData()} as a {@code byte[]}, or decoded into a {@code String}
     * (empty text yields {@code null}).
     *
     * @param request the inbound HTTP request
     * @param config  the converter configuration
     * @param type    the target converted type ({@code InputStream}, {@code byte[]} or {@code String})
     * @return the resolved body value, or {@code null} when unsupported in stream mode or
     *         the decoded text is empty
     * @throws Exception if reading the body fails
     */
    @Override
    public Object read(HttpRequest request, ConverterConfig config, Type type) throws Exception {
        if(type == InputStream.class) return request.bodyStream();
        if(request.isStream()) {
            return type == String.class ? readString(request.bodyStream()) : null;
        }
        byte[] data = request.getBodyData();
        if(type == byte[].class) return data;
        return data.length == 0 ? null : new String(data, StandardCharsets.UTF_8);
    }

    // Read the streamed body as a UTF-8 string (byte-level collect, JDK-optimized decode).
    private static String readString(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}

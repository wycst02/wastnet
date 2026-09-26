package io.github.wycst.wastnet.http.annotation;

import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;

import java.lang.reflect.Type;

/**
 * SPI for serializing/deserializing HTTP message bodies.
 * <p>
 * Implement this to support automatic JSON (or other format) conversion.
 * Typically backed by Jackson, Gson, or Fastjson.
 *
 * @author wangyc
 */
public interface HttpMessageConverter {

    /**
     * Deserialize the request body into the given type.
     * <p>
     * Called for {@code @RequestBody} parameters. Check {@code request.isStream()}
     * before reading the full body. {@code type} is a full {@link Type}, so generics
     * like {@code List<User>} are preserved (unlike a raw {@link Class}).
     *
     * @param request the HTTP request
     * @param config  per-endpoint conversion config
     * @param type    target type (may be a generic {@code Type})
     * @return deserialized object, or {@code null} if body is empty/streaming
     * @throws Exception if deserialization fails
     */
    Object read(HttpRequest request, ConverterConfig config, Type type) throws Exception;

    /**
     * Serialize the controller return value to the response.
     * <p>
     * Called when the method returns a non-void value and a converter is configured.
     *
     * @param value    the return value
     * @param config   per-endpoint conversion config
     * @param response the HTTP response to write to
     * @throws Exception if serialization or I/O fails
     */
    void write(Object value, ConverterConfig config, HttpResponse response) throws Exception;
}

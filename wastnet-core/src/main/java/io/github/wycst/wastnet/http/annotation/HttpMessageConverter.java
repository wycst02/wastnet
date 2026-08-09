package io.github.wycst.wastnet.http.annotation;

import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;

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
     * Deserialize the request body into the target type.
     * <p>
     * Called when a controller method's parameter types indicate a request body
     * should be deserialized into a POJO. Implementations should check
     * {@code request.isStream()} before reading the full body.
     *
     * @param request  the HTTP request
     * @param config   per-endpoint conversion config
     * @param type     the target type to deserialize into
     * @param <T>      the target type
     * @return deserialized object, or {@code null} if body is empty or streaming
     * @throws Exception if deserialization fails
     */
    <T> T read(HttpRequest request, ConverterConfig config, Class<T> type) throws Exception;

    /**
     * Serialize the return value to the response.
     * <p>
     * Called when a controller method returns a non-void value and a
     * {@code HttpMessageConverter} has been configured.
     *
     * @param value    the return value from the controller method
     * @param config   per-endpoint conversion config
     * @param response the HTTP response to write to
     * @throws Exception if serialization or I/O fails
     */
    void write(Object value, ConverterConfig config, HttpResponse response) throws Exception;
}

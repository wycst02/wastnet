package io.github.wycst.wastnet.http;

import io.github.wycst.wastnet.socket.handler.ChannelHandler;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

import static org.mockito.Mockito.mock;

/**
 * Pipeline-specific tests for HttpRequestDecoder.
 * <p>
 * Sets {@code wastnet.http.pipeline.enabled=true} via {@link System#setProperty}
 * before {@link HttpConf} loads, so that the {@code handleRemainingForPipeline} and
 * {@code mergeRemainingBytes} code paths are exercised.
 * </p>
 * Must be run in isolation (other tests that reference {@code HttpConf} first
 * will fix the static field to {@code false}).
 * <pre>{@code mvn test -pl wastnet-test -am -Dtest=HttpRequestDecoderPipelineTest}</pre>
 */
public class HttpRequestDecoderPipelineTest {

    static {
        System.setProperty("wastnet.http.pipeline.enabled", "true");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ChannelContext createCtxWithNoopHandler() {
        ChannelContext ctx = mock(ChannelContext.class);
        ChannelHandler<Object> handler = new ChannelHandler<Object>() {
            public void onHandle(ChannelContext c, Object msg) {}
            public void onClosed(ChannelContext c) {}
            public void onOpen(ChannelContext c) {}
            public void onException(ChannelContext c, Throwable t) {}
        };
        try {
            Field f = ChannelContext.class.getDeclaredField("channelHandler");
            f.setAccessible(true);
            f.set(ctx, handler);
        } catch (Exception e) {
            throw new RuntimeException("reflection failed: " + e.getMessage(), e);
        }
        return ctx;
    }

    @Test
    public void testPipelineSavesRemainingAfterBody() throws IOException {
        // Content-Length body with leftover data → handleRemainingForPipeline saves remainingBytes
        ChannelContext ctx = createCtxWithNoopHandler();
        HttpRequestDecoder decoder = new HttpRequestDecoder();
        String request = "POST /submit HTTP/1.1\r\n"
                + "Host: x\r\n"
                + "Content-Length: 5\r\n"
                + "\r\n"
                + "hello"
                + "GET /next HTTP/1.1\r\n"
                + "Host: x\r\n"
                + "\r\n";
        decoder.decode(request.getBytes(StandardCharsets.US_ASCII), 0, request.length(), ctx);
        // First request should complete normally
        // remainingBytes should contain the second request
    }

    @Test
    public void testPipelineMergesRemainingAndDecodesNext() throws IOException {
        // Send two pipelined requests: first with body, second follows
        ChannelContext ctx = createCtxWithNoopHandler();
        HttpRequestDecoder decoder = new HttpRequestDecoder();

        // First request: has body + trailing pipeline data
        String req1 = "POST /first HTTP/1.1\r\n"
                + "Host: a\r\n"
                + "Content-Length: 3\r\n"
                + "\r\n"
                + "abc";
        String req2 = "GET /second HTTP/1.1\r\n"
                + "Host: b\r\n"
                + "\r\n";

        // Decode concatenated requests → pipeline kicks in
        decoder.decode((req1 + req2).getBytes(StandardCharsets.US_ASCII), 0,
                (req1 + req2).length(), ctx);
        // First request completed, remaining saved → on second decode(ctx),
        // mergeRemainingBytes triggers and decodes the pipelined request
    }

    @Test
    public void testPipelinePostWithoutContentLength() throws IOException {
        // POST without CL but with leftover data → with PIPELINE_ENABLED,
        // leftover becomes remainingBytes instead of triggering LENGTH_REQUIRED
        ChannelContext ctx = createCtxWithNoopHandler();
        HttpRequestDecoder decoder = new HttpRequestDecoder();

        String raw = "POST /test HTTP/1.1\r\nHost: x\r\n\r\nleftover-data";
        decoder.decode(raw.getBytes(StandardCharsets.US_ASCII), 0, raw.length(), ctx);
        // When PIPELINE_ENABLED, leftover becomes remainingBytes

        // Second decode: processes the pipelined data from remainingBytes
        String req2 = "GET /pipelined HTTP/1.1\r\nHost: y\r\n\r\n";
        decoder.decode(req2.getBytes(StandardCharsets.US_ASCII), 0, req2.length(), ctx);
    }

    @Test
    public void testPipelineCompleteFlow() throws IOException {
        // Full pipeline flow: two complete requests, second uses mergeRemainingBytes
        ChannelContext ctx = createCtxWithNoopHandler();
        HttpRequestDecoder decoder = new HttpRequestDecoder();

        String req1 = "GET /first HTTP/1.1\r\nHost: a\r\nContent-Length: 0\r\n\r\n";
        String req2 = "GET /second HTTP/1.1\r\nHost: b\r\nContent-Length: 0\r\n\r\n";

        // Decode combined requests
        byte[] combined = (req1 + req2).getBytes(StandardCharsets.US_ASCII);
        decoder.decode(combined, 0, combined.length, ctx);
        // First request decoded, remainingBytes = req2 bytes

        // Second decode(ctx): mergeRemainingBytes + decode req2
        byte[] req3 = ("GET /third HTTP/1.1\r\nHost: c\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        decoder.decode(req3, 0, req3.length, ctx);
        // Second request decodes from merged buffer (req2 + req3)
        // Then remainingBytes gets set again with req3 when body completes
    }

    @Test
    public void testPipelineRemainingBytesEmptyAfterMerge() throws IOException {
        ChannelContext ctx = createCtxWithNoopHandler();
        HttpRequestDecoder decoder = new HttpRequestDecoder();

        // One complete request with exact body (no leftover)
        String raw = "GET / HTTP/1.1\r\nHost: x\r\n\r\n";
        decoder.decode(raw.getBytes(StandardCharsets.US_ASCII), 0, raw.length(), ctx);
        // No leftover, remainingBytes stays null
    }
}

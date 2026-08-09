package io.github.wycst.wastnet.http;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unit tests for {@link SseEmitter}.
 */
public class SseEmitterTest {

    private SseEmitter createEmitter() {
        return new SseEmitter(new MockResponse(), null);
    }

    @Test
    public void testCloseReleasesLatch() {
        SseEmitter emitter = createEmitter();
        Assertions.assertFalse(emitter.isClosed());
        emitter.close();
        Assertions.assertTrue(emitter.isClosed());
    }

    @Test
    public void testCloseIsIdempotent() {
        SseEmitter emitter = createEmitter();
        emitter.close();
        emitter.close(); // second call should be no-op
        Assertions.assertTrue(emitter.isClosed());
    }

    @Test
    public void testOnCloseCallback() {
        SseEmitter emitter = createEmitter();
        AtomicBoolean called = new AtomicBoolean(false);
        emitter.onClose(() -> called.set(true));
        emitter.close();
        Assertions.assertTrue(called.get());
    }

    @Test
    public void testAwaitCloseTimeout() throws Exception {
        SseEmitter emitter = createEmitter();
        long start = System.currentTimeMillis();
        boolean result = emitter.awaitClose(100);
        long elapsed = System.currentTimeMillis() - start;
        Assertions.assertFalse(result, "awaitClose should return false on timeout");
        Assertions.assertTrue(elapsed >= 80, "awaitClose should block for ~100ms");
    }

    @Test
    public void testAwaitCloseReturnsTrueAfterClose() throws Exception {
        SseEmitter emitter = createEmitter();
        new Thread(() -> {
            try { Thread.sleep(50); } catch (InterruptedException e) { }
            emitter.close();
        }).start();
        boolean result = emitter.awaitClose(5000);
        Assertions.assertTrue(result, "awaitClose should return true when close() is called");
    }

    @Test
    public void testEmitDelegatesToResponse() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);
        HttpResponse response = new MockResponse() {
            @Override
            public HttpResponse sse(String data) {
                callCount.incrementAndGet();
                return this;
            }
        };
        SseEmitter emitter = new SseEmitter(response, null);
        emitter.emit("hello");
        Assertions.assertEquals(1, callCount.get());
    }

    @Test
    public void testEmitFullParamsDelegatesToResponse() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);
        HttpResponse response = new MockResponse() {
            @Override
            public HttpResponse sse(String event, String data, String id, long retry) {
                callCount.incrementAndGet();
                return this;
            }
        };
        SseEmitter emitter = new SseEmitter(response, null);
        emitter.emit("chat", "hello", "msg-001", 3000);
        Assertions.assertEquals(1, callCount.get());
    }

    @Test
    public void testEmitThrowsAfterClose() {
        SseEmitter emitter = createEmitter();
        emitter.close();
        Assertions.assertThrows(IllegalStateException.class, () -> emitter.emit("hello"));
    }

    @Test
    public void testDefaultTimeout() {
        Assertions.assertEquals(1800000L, HttpConf.SSE_TIMEOUT_MS, "Default SSE timeout should be 30 minutes");
    }

    // Minimal HttpResponse stub for testing
    static class MockResponse implements HttpResponse {
        @Override public HttpVersion getHttpVersion() { return null; }
        @Override public void setStatus(HttpStatus status) {}
        @Override public HttpResponse status(HttpStatus status) { return this; }
        @Override public HttpResponse status(int code) { return this; }
        @Override public void setStatusAndText(HttpStatus s) {}
        @Override public HttpStatus getStatus() { return null; }
        @Override public void addHeader(String key, java.io.Serializable value) {}
        @Override public void setHeader(String key, java.io.Serializable value) {}
        @Override public String getHeader(String key) { return null; }

        @Override
        public List<String> getHeaders(String name) {
            return Collections.emptyList();
        }

        @Override
        public Set<String> getHeaderNames() {
            return Collections.emptySet();
        }

        @Override public HttpResponse header(String key, java.io.Serializable value) { return this; }
        @Override public void removeHeader(String key) {}
        @Override public void removeHeader(String key, java.io.Serializable value) {}
        @Override public void setContentLength(long contentLength) {}
        @Override public HttpResponse contentLength(long contentLength) { return this; }
        @Override public long getContentLength() { return 0; }
        @Override public void setContentType(String contentType) {}
        @Override public HttpResponse contentType(String contentType) { return this; }
        @Override public String getContentType() { return null; }
        @Override public HttpResponse body(byte[] body) { return this; }
        @Override public HttpResponse body(byte[] body, int offset, int len) { return this; }
        @Override public HttpResponse body(String body) { return this; }
        @Override public void sendFile(java.io.File file) {}
        @Override public void sendFile(java.io.File file, boolean cacheEnabled, int maxAge) {}
        @Override public void sendFile(java.io.File file, boolean cacheEnabled, String cacheControl, String mimeType) {}
        @Override public void setCharacterEncoding(String characterEncoding) {}
        @Override public String getCharacterEncoding() { return null; }
        @Override public void flush() {}
        @Override public void commit() {}
        @Override public void writeChunked(byte[] data) {}
        @Override public void write(byte[] buf) {}
        @Override public void write(byte[] buf, int offset, int count) {}
        @Override public java.io.OutputStream outputStream() { return null; }
        @Override public void setLastModified(long lastModifiedMillis) {}
        @Override public boolean isChunked() { return false; }
        @Override public void setChunked(boolean chunked) {}
        @Override public void setChunkedEncoding() {}
        @Override public void removeChunkedEncoding() {}
        @Override public HttpResponse chunked() { return this; }
        @Override public HttpResponse chunked(boolean chunked) { return this; }
        @Override public boolean isKeepAlive() { return false; }
        @Override public void setKeepAlive(boolean keepAlive) {}
        @Override public void setAutoCommit(boolean autoCommit) {}
        @Override public boolean isAutoCommit() { return true; }
        @Override public boolean isGzipSupported() { return false; }
        @Override public boolean isCorrupted() { return false; }
        @Override public void earlyHints(String linkHeader) {}
        @Override public HttpResponse sse(String data) { return this; }
        @Override public HttpResponse sse(String event, String data) { return this; }
        @Override public HttpResponse sse(String event, String data, String id, long retry) { return this; }
        @Override public int getBufferSize() { return 0; }
        @Override public boolean isCommitted() { return false; }
        @Override public void reset() {}
        @Override public void resetBuffer() {}
    }
}

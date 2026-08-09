package io.github.wycst.wastnet.http.h2;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Test utility to initialize fields on mocked Http2MessageReader instances.
 * Mocks created via Mockito have all fields set to null; this helper
 * sets up the {@code flushPending} field required by {@code signalFlush}.
 */
public final class H2TestHelper {

    public static void initMockReader(Http2MessageReader mockReader) {
        try {
            Field f = Http2MessageReader.class.getDeclaredField("flushPending");
            f.setAccessible(true);
            f.set(mockReader, new AtomicBoolean());
        } catch (Exception ignored) {
        }
    }
}

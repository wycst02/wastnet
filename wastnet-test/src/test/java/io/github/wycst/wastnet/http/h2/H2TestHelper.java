package io.github.wycst.wastnet.http.h2;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import io.github.wycst.wastnet.http.HttpConf;

/**
 * Test utility to initialize fields on mocked Http2MessageReader instances.
 * Mocks created via Mockito have all fields set to null; this helper sets up the
 * fields that Http2MessageReader's constructor would normally set from ctx.option(...).
 * Mockito mocks reject plain Field.set, so we write the values via Unsafe (same approach
 * already used in Http2ServerStreamTest for the final http2HpackCodec field).
 */
public final class H2TestHelper {

    private static Object theUnsafe() {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            return theUnsafe.get(null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        Class<?> c = clazz;
        while (c != null) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static long offset(Object unsafe, Class<?> targetClass, String name) {
        try {
            Field f = findField(targetClass, name);
            return (long) unsafe.getClass().getMethod("objectFieldOffset", Field.class).invoke(unsafe, f);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void put(Object unsafe, Object target, String name, Object value) {
        try {
            long off = offset(unsafe, target.getClass(), name);
            unsafe.getClass().getMethod("putObject", Object.class, long.class, Object.class)
                    .invoke(unsafe, target, off, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void putInt(Object unsafe, Object target, String name, int value) {
        try {
            long off = offset(unsafe, target.getClass(), name);
            unsafe.getClass().getMethod("putInt", Object.class, long.class, int.class)
                    .invoke(unsafe, target, off, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void putBoolean(Object unsafe, Object target, String name, boolean value) {
        try {
            long off = offset(unsafe, target.getClass(), name);
            unsafe.getClass().getMethod("putBoolean", Object.class, long.class, boolean.class)
                    .invoke(unsafe, target, off, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void initMockReader(Http2MessageReader mockReader) {
        Object unsafe = theUnsafe();
        put(unsafe, mockReader, "flushPending", new AtomicBoolean());
        putBoolean(unsafe, mockReader, "streamEarly", HttpConf.HTTP2_STREAM_EARLY);
        put(unsafe, mockReader, "connectRecvWindow", new AtomicLong((long) HttpConf.HTTP2_INITIAL_SEND_WINDOW_SIZE << 4));
        putInt(unsafe, mockReader, "initConnectReceiveWindowSize", HttpConf.HTTP2_INITIAL_SEND_WINDOW_SIZE << 4);
        putInt(unsafe, mockReader, "initialReceiveWindowSize", HttpConf.HTTP2_INITIAL_SEND_WINDOW_SIZE);
        putInt(unsafe, mockReader, "maxStreamCapacitySize",
                Math.max(HttpConf.HTTP2_INITIAL_SEND_WINDOW_SIZE << 1, HttpConf.MAX_BODY_IN_MEMORY));
        // Fields populated from ctx.option(...) in the real constructor; mocks need them too.
        putInt(unsafe, mockReader, "maxHttpHeaderSize", HttpConf.MAX_HTTP_HEADER_SIZE);
        putInt(unsafe, mockReader, "flowControlWaitTimeoutMs", HttpConf.HTTP2_FLOW_CONTROL_WAIT_TIMEOUT_MS);
    }

    /** Install an Http2HpackCodec into a mocked reader (the final http2HpackCodec field is null on mocks). */
    public static void installCodec(Http2MessageReader mockReader) {
        put(theUnsafe(), mockReader, "http2HpackCodec", new Http2HpackCodec());
    }

    /** Override the final connectRecvWindow field with a specific window size (Unsafe). */
    public static void setConnectRecvWindow(Http2MessageReader mockReader, long size) {
        put(theUnsafe(), mockReader, "connectRecvWindow", new AtomicLong(size));
    }

    /** Override the final streamEarly field (allows covering the non-early streaming branch in onDataFrame). */
    public static void setStreamEarly(Http2MessageReader mockReader, boolean early) {
        putBoolean(theUnsafe(), mockReader, "streamEarly", early);
    }

    /** Override the final maxStreamCapacitySize field (controls the capacity-reached branch in onDataFrame). */
    public static void setMaxStreamCapacitySize(Http2MessageReader mockReader, int size) {
        putInt(theUnsafe(), mockReader, "maxStreamCapacitySize", size);
    }
}

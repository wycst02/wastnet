package io.github.wycst.wastnet.http.upgrade.websocket;

import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.log.Log;
import io.github.wycst.wastnet.log.LogFactory;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.concurrent.ScheduledFuture;

/**
 * Default WebSocket connection implementation
 * Implements WebSocket connection functionality based on HTTP upgrade protocol
 *
 * @since 2024-1-27
 * @author wangyc
 */
final class WebSocketConnectionImpl implements WebSocketConnection {

    private static final Log log = LogFactory.getLog(WebSocketConnectionImpl.class);

    private final ChannelContext ctx;
    private final WebSocketResponse response;
    private final HttpRequest request;
    private boolean closed = false;
    private Serializable account;
    private Serializable groupId;
    // Last active timestamp
    private volatile long lastActiveTime = System.currentTimeMillis();
    // Timeout task Future
    private ScheduledFuture<?> timeoutTaskFuture;
    private Runnable detectionTask;

    static final byte[] PING_FRAME = new byte[]{(byte) 0x89, 0x00};
    static final byte[] PONG_FRAME = new byte[]{(byte) 0x8A, 0x00};
    static final byte[] CONTINUATION_END_FRAME = new byte[]{(byte) 0x80, 0x00};
    static final byte[] BINARY_EMPTY_FRAME = new byte[]{(byte) 0x82, 0x00};

    public WebSocketConnectionImpl(HttpRequest request, WebSocketResponse response, ChannelContext ctx) {
        this.request = request;
        this.response = response;
        this.ctx = ctx;
    }

    @Override
    public void sendText(String message) throws IOException {
        if (closed) {
            throw new IllegalStateException("WebSocket connection is closed");
        }
        if (message == null) {
            throw new IllegalArgumentException("Message cannot be null");
        }
        response.sendText(message);
    }

    @Override
    public void sendBinary(byte[] data) throws IOException {
        if (closed) {
            throw new IllegalStateException("WebSocket connection is closed");
        }
        if (data == null) {
            throw new IllegalArgumentException("Data cannot be null");
        }
        response.sendBinary(data);
    }

    @Override
    public void sendFile(File file) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("File cannot be null");
        }
        sendInputStream(Files.newInputStream(file.toPath()));
    }

    @Override
    public void sendInputStream(InputStream in) throws IOException {
        if (closed) {
            throw new IllegalStateException("WebSocket connection is closed");
        }
        if (in == null) {
            throw new IllegalArgumentException("InputStream cannot be null");
        }
        try {
            final int chunkSize = getChunkSize(), headerLen = WebSocketUtils.frameHeaderLength(chunkSize), frameLen = headerLen + chunkSize;
            byte[] buf = new byte[frameLen];
            WebSocketUtils.writeServerFrameHeader(WebSocketFrame.FrameType.BINARY, chunkSize, false, buf, 0);

            int len = readGreedy(in, buf, headerLen, chunkSize);
            if (len <= 0) {
                response.writeFlush(BINARY_EMPTY_FRAME);
                return;
            }

            // First chunk: BINARY frame (header already pre-written)
            boolean last = len < chunkSize;
            if (last) {
                sendLastFrame(WebSocketFrame.FrameType.BINARY, len, buf, headerLen);
            } else {
                ctx.writeFlush(ByteBuffer.wrap(buf, 0, frameLen));
            }
            buf[0] = (byte) (WebSocketFrame.FrameType.CONTINUATION.opcode & 0x0F);

            // Subsequent chunks: always CONTINUATION
            while (!last && (len = readGreedy(in, buf, headerLen, chunkSize)) != -1) {
                last = len < chunkSize;
                if (last) {
                    sendLastFrame(WebSocketFrame.FrameType.CONTINUATION, len, buf, headerLen);
                    break;
                }
                ctx.writeFlush(ByteBuffer.wrap(buf, 0, frameLen));
            }
            if (!last) {
                response.writeFlush(CONTINUATION_END_FRAME);
            }
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }



    /**
     * Read greedily: fill the buffer starting at {@code off} until {@code len} bytes
     * are read or EOF is reached. Returns total bytes read, or -1 if nothing was read.
     */
    private static int readGreedy(InputStream in, byte[] b, int off, int len) throws IOException {
        int total = 0, r;
        while (total < len && (r = in.read(b, off + total, len - total)) != -1) {
            total += r;
        }
        return total > 0 ? total : -1;
    }

    private void sendLastFrame(WebSocketFrame.FrameType type, int len, byte[] buf, int headerLen) throws IOException {
        int lastHdrLen = WebSocketUtils.frameHeaderLength(len);
        int off = headerLen - lastHdrLen;
        WebSocketUtils.writeServerFrameHeader(type, len, true, buf, off);
        ctx.writeFlush(ByteBuffer.wrap(buf, off, lastHdrLen + len));
    }

    private int getChunkSize() {
        int maxSize = WebSocketUtils.getMaxPayloadSize(ctx);
        return maxSize > 0 ? maxSize : 65535;
    }

    @Override
    public void ping() throws IOException {
        if (closed) {
            throw new IllegalStateException("WebSocket connection is closed");
        }
        response.writeFlush(PING_FRAME);
    }

    @Override
    public void pong() throws IOException {
        if (closed) {
            throw new IllegalStateException("WebSocket connection is closed");
        }
        response.writeFlush(PONG_FRAME);
    }

    @Override
    public void push(WebSocketFrame frame) {
        if (frame == null) return;
        try {
            response.writeFlush(frame.data);
        } catch (Throwable throwable) {
            throw new IllegalStateException("Failed to push message to WebSocket connection", throwable);
        }
    }

    @Override
    public void close(int code, String reason) throws IOException {
        try {
            if (closed) return;
            response.close(code, reason);
        } catch (IOException e) {
            log.error("Failed to close WebSocket connection", e);
            throw e;
        } finally {
            stopTimeoutDetection();
            try {
                ctx.close();
            } catch (Exception e) {
                log.error("Failed to close channel context", e);
            }
            closed = true;
        }
    }

    @Override
    public boolean isClosed() {
        return closed || ctx.isChannelClosed();
    }

    @Override
    public HttpRequest request() {
        return request;
    }

    @Override
    public String subprotocol() {
        return response.subprotocol();
    }

    public String id() {
        return ctx.contextId();
    }

    @Override
    public Serializable getAccount() {
        return account;
    }

    @Override
    public void setAccount(Serializable account) {
        this.account = account;
    }

    @Override
    public void setGroupId(Serializable groupId) {
        this.groupId = groupId;
    }

    @Override
    public Serializable getGroupId() {
        return groupId;
    }

    @Override
    public void disconnect() {
        if (closed) return;
        try {
            close(1000, "Disconnected");
        } catch (IOException e) {
            log.error("Failed to close WebSocket connection", e);
        } finally {
            try {
                ctx.close();
            } catch (Exception e) {
                log.error("Failed to close channel context", e);
            }
            closed = true;
        }
    }

    public void setAttribute(String key, Object value) {
        ctx.setAttribute(key, value);
    }

    public Object getAttribute(String key) {
        return ctx.getAttribute(key);
    }

    @Override
    public void updateActiveTime() {
        lastActiveTime = System.currentTimeMillis();
    }

    /**
     * Stop timeout detection
     */
    private void stopTimeoutDetection() {
        if (timeoutTaskFuture != null) {
            try {
                if (!timeoutTaskFuture.isCancelled()) {
                    timeoutTaskFuture.cancel(false);
                }
            } catch (Throwable ignored) {
            }
            timeoutTaskFuture = null;
        }
        detectionTask = null;
    }

    /**
     * Schedule timeout detection task
     *
     * @param delay Delay time in milliseconds
     */
    private void scheduleDetectionTask(long delay) {
        timeoutTaskFuture = ctx.schedule(detectionTask, delay);
    }

    @Override
    public void timeoutDetection(int timeout, WebSocketResource.TimeoutStrategy strategy) {
        stopTimeoutDetection();
        if (timeout <= 0) {
            return;
        }
        initDetectionTask(timeout, strategy);
        scheduleDetectionTask(timeout * 1000L);
    }

    private void initDetectionTask(final int timeout, final WebSocketResource.TimeoutStrategy strategy) {
        if (detectionTask == null) {
            detectionTask = () -> {
                if (closed) return;
                long now = System.currentTimeMillis();
                long elapsed = now - lastActiveTime;
                final long timeoutDelay = timeout * 1000L;
                if (elapsed >= timeoutDelay) {
                    if (elapsed >= timeoutDelay << 1) {
                        // Regardless of the strategy, if the timeout period is exceeded by more than twice, the connection will be disconnected
                        // If no pong packet (or any other packet) is received after sending a ping packet, the probing will be stopped and the connection will be closed directly
                        log.warn("WebSocket connection timed out for {} seconds", elapsed / 1000);
                        disconnect();
                        return;
                    }
                    if (strategy == WebSocketResource.TimeoutStrategy.PING) {
                        try {
                            ping();
                            scheduleDetectionTask(timeoutDelay);
                        } catch (IOException e) {
                            log.error("Failed to send ping for timeout detection", e);
                            disconnect();
                        }
                    } else {
                        disconnect();
                    }
                } else {
                    long remaining = timeoutDelay - elapsed;
                    scheduleDetectionTask(remaining);
                }
            };
        }
    }
}
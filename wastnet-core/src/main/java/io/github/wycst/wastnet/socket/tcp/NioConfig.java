package io.github.wycst.wastnet.socket.tcp;

import io.github.wycst.wastnet.socket.channel.ChannelReader;
import io.github.wycst.wastnet.socket.channel.ChannelReaderFactory;
import io.github.wycst.wastnet.socket.conf.Option;
import io.github.wycst.wastnet.socket.conf.SocketConf;
import io.github.wycst.wastnet.socket.handler.ChannelHandler;
import io.github.wycst.wastnet.socket.handler.ClearableHandler;
import io.github.wycst.wastnet.socket.handler.IdleStateHandler;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * NIO engine configuration (shared by TCPServer and TCPClient)
 *
 * @since 2024-1-20
 * @author wangyc
 */
public final class NioConfig {

    // Default maximum workers: 4 times the computed defaultWorkerNum (acts as upper bound)
    private static final int MAX_WORKER_NUM;
    private static final int defaultWorkerNum;
    private static int defaultBufferSize = 1024;

    static {
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        int hob = Integer.highestOneBit(availableProcessors);
        int computed = hob == availableProcessors ? availableProcessors : hob >> 1;
        defaultWorkerNum = Math.max(2, computed);
        MAX_WORKER_NUM = defaultWorkerNum << 2;
    }

    private int readBufferSize = defaultBufferSize;
    private int writeBufferSize = defaultBufferSize;

    private int workerNum = defaultWorkerNum;

    private boolean printSSLErrorLog;

    private boolean syncRunner = SocketConf.DEFAULT_SYNC_RUNNER;
    private boolean printReadErrorLog;
    private boolean printApplicationMessage;
    private ChannelReader<?> channelReader = ChannelReader.UNDO;
    private ChannelHandler<?> channelHandler;
    private IdleStateHandler idleStateHandler;
    private ChannelReaderFactory channelReaderFactory = singletonChannelReaderFactory();

    private boolean allowPlaintextWhenSslEnabled;

    private String[] applicationProtocols;

    private String[] enabledProtocols;

    private ConnectionFilter connectionFilter;

    // ================= Per-instance options =================
    // Strongly-typed per-server overrides; absent keys fall back to Option.defaultValue.
    // Option instances are used directly as keys, so no string keys are needed.
    private final Map<Option<?>, Object> options = new HashMap<>();

    /**
     * Get a strongly-typed configuration option value for this server instance.
     *
     * @param option the option to read (e.g. {@code HttpOptions.GZIP})
     * @param <T>    value type
     * @return the instance override if present, otherwise {@code option.defaultValue}
     */
    @SuppressWarnings("unchecked")
    public <T> T option(Option<T> option) {
        if (option == null) {
            throw new IllegalArgumentException("option must not be null");
        }
        Object value = options.get(option);
        if (value == null) {
            return option.value;
        }
        return (T) value;
    }

    /**
     * Configure a per-instance option override (fluent style, e.g.
     * {@code nioConfig.option(HttpOptions.GZIP, false)}).
     *
     * @param option the option to override
     * @param value  the override value (typed as {@code T} by the option)
     * @param <T>    value type
     * @return this NioConfig instance
     */
    public <T> NioConfig option(Option<T> option, T value) {
        if (option == null) {
            throw new IllegalArgumentException("option must not be null");
        }
        if (value != null) {
            value = option.normalizer.apply(value);
        }
        options.put(option, value);
        return this;
    }

    public String[] getApplicationProtocols() {
        return applicationProtocols;
    }

    public void setApplicationProtocols(String[] applicationProtocols) {
        this.applicationProtocols = applicationProtocols;
    }

    public String[] getEnabledProtocols() {
        return enabledProtocols;
    }

    public void setEnabledProtocols(String[] enabledProtocols) {
        this.enabledProtocols = enabledProtocols;
    }

    public NioConfig enabledProtocols(String... enabledProtocols) {
        this.enabledProtocols = enabledProtocols;
        return this;
    }

    public boolean isSyncRunner() {
        return syncRunner;
    }

    public void setSyncRunner(boolean syncRunner) {
        this.syncRunner = syncRunner;
    }

    // Test-only mode: enables pipelining, sync runner and plaintext-over-ssl. Not for production.
    public NioConfig testMode() {
        System.setProperty("wastnet.http.pipeline.enabled", "true");
        setSyncRunner(true);
        setAllowPlaintextWhenSslEnabled(true);
        return this;
    }

    public void setReadBufferSize(int readBufferSize) {
        this.readBufferSize = Math.max(readBufferSize, 512);
    }

    public int getReadBufferSize() {
        return readBufferSize;
    }

    public int getWriteBufferSize() {
        return writeBufferSize;
    }

    public void setWriteBufferSize(int writeBufferSize) {
        this.writeBufferSize = Math.max(writeBufferSize, 512);
    }

    public NioConfig self() {
        return this;
    }

    public void setWorkerNum(int workerNum) {
        workerNum = workerNum > 1 ? Integer.highestOneBit(workerNum) : 2;
        if (workerNum > MAX_WORKER_NUM) {
            workerNum = MAX_WORKER_NUM;
        }
        this.workerNum = workerNum;
    }

    public int getWorkerNum() {
        return workerNum;
    }

    public static void setDefaultBufferSize(int defaultBufferSize) {
        NioConfig.defaultBufferSize = Math.max(defaultBufferSize, 256);
    }

    public boolean isPrintSSLErrorLog() {
        return printSSLErrorLog;
    }

    public void setPrintSSLErrorLog(boolean printSSLErrorLog) {
        this.printSSLErrorLog = printSSLErrorLog;
    }

    public boolean isPrintReadErrorLog() {
        return printReadErrorLog;
    }

    public void setPrintReadErrorLog(boolean printReadErrorLog) {
        this.printReadErrorLog = printReadErrorLog;
    }

    public boolean isPrintApplicationMessage() {
        return printApplicationMessage;
    }

    public void setPrintApplicationMessage(boolean printApplicationMessage) {
        this.printApplicationMessage = printApplicationMessage;
    }

    public void setIdleStateHandler(IdleStateHandler idleStateHandler) {
        this.idleStateHandler = idleStateHandler;
    }

    public IdleStateHandler getIdleStateHandler() {
        return idleStateHandler;
    }

    public void setChannelHandler(ChannelHandler<?> channelHandler) {
        this.channelHandler = channelHandler;
    }

    public ChannelHandler<?> getChannelHandler() {
        return channelHandler;
    }

    public ChannelReader<?> getChannelReader() {
        ChannelReader<?> channelReader = channelReaderFactory.getChannelReader();
        return channelReader == null ? ChannelReader.UNDO : channelReader;
    }

    public void setChannelReader(ChannelReader<?> channelReader) {
        this.channelReader = Objects.requireNonNull(channelReader, "channelReader must not be null");
    }

    public void setChannelReaderFactory(ChannelReaderFactory channelReaderFactory) {
        this.channelReaderFactory = Objects.requireNonNull(channelReaderFactory, "channelReaderFactory must not be null");
    }

    ChannelReaderFactory singletonChannelReaderFactory() {
        return () -> channelReader;
    }

    public boolean isAllowPlaintextWhenSslEnabled() {
        return allowPlaintextWhenSslEnabled;
    }

    public void setAllowPlaintextWhenSslEnabled(boolean allowPlaintextWhenSslEnabled) {
        this.allowPlaintextWhenSslEnabled = allowPlaintextWhenSslEnabled;
    }

    public ConnectionFilter getConnectionFilter() {
        return connectionFilter;
    }

    public void setConnectionFilter(ConnectionFilter connectionFilter) {
        this.connectionFilter = connectionFilter;
    }

    public void clear() {
        if (channelHandler instanceof ClearableHandler) {
            ((ClearableHandler) channelHandler).clear();
        }
        if (channelReader instanceof ClearableHandler) {
            ((ClearableHandler) channelReader).clear();
        }
        if (channelReaderFactory instanceof ClearableHandler) {
            ((ClearableHandler) channelReaderFactory).clear();
        }
        if(idleStateHandler instanceof ClearableHandler) {
            ((ClearableHandler) idleStateHandler).clear();
        }
    }
}

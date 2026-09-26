/*
 * Copyright 2026, wangyunchao.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.wycst.wastnet.socket.tcp;

import io.github.wycst.wastnet.exception.SocketException;
import io.github.wycst.wastnet.log.Log;
import io.github.wycst.wastnet.log.LogFactory;
import io.github.wycst.wastnet.socket.channel.ChannelCodec;
import io.github.wycst.wastnet.socket.channel.ChannelReader;
import io.github.wycst.wastnet.socket.channel.ChannelReaderFactory;
import io.github.wycst.wastnet.socket.conf.Option;
import io.github.wycst.wastnet.socket.conf.SocketOptions;
import io.github.wycst.wastnet.socket.handler.ChannelHandler;
import io.github.wycst.wastnet.socket.handler.IdleStateHandler;
import io.github.wycst.wastnet.util.Utils;

import javax.net.ssl.SSLContext;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.*;

/**
 * NIO engine base class shared by TCPServer and TCPClient.
 * <p>
 * Manages the common NIO infrastructure: worker threads, thread pools,
 * SSL/TLS configuration, and lifecycle (stop/shutdown).
 *
 * @since 2024-1-14
 * @author wangyc
 */
public class NioEngine<E extends NioEngine<E>> {

    protected final Log LOG = LogFactory.getLog(getClass());
    final ExecutorService executorService;
    final ExecutorService runnerExecutor;
    protected final int port;
    protected final NioConfig nioConfig;
    volatile boolean engineRunFlag = false;
    boolean shutdown = false;
    /** Latch for waiting on worker threads during startup. */
    volatile CountDownLatch startLatch;
    // ssl
    boolean ssl;
    SSLContext sslCtx;
    SSLContextFactory sslContextFactory;
    String[] sslCipherSuites;

    public NioEngine(int port) {
        this(port, new NioConfig());
    }

    public NioEngine(int port, NioConfig nioConfig) {
        if (port <= 0) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        this.port = port;
        this.nioConfig = nioConfig == null ? new NioConfig() : nioConfig;
        this.executorService = Executors.newCachedThreadPool();
        this.runnerExecutor = createExecutor();
    }

    @SuppressWarnings("unchecked")
    private E self() {
        return (E) this;
    }

    public final int getPort() {
        return port;
    }

    // ==================== Configuration (fluent, returns E for chaining) ====================

    public final NioConfig config() {
        return nioConfig;
    }

    public E idleStateHandler(IdleStateHandler idleStateHandler) {
        nioConfig.setIdleStateHandler(idleStateHandler);
        return self();
    }

    public E channelHandler(ChannelHandler<?> channelHandler) {
        nioConfig.setChannelHandler(channelHandler);
        return self();
    }

    public E channelReader(ChannelReader channelReader) {
        nioConfig.setChannelReader(channelReader);
        return self();
    }

    public E channelReaderFactory(ChannelReaderFactory channelReaderFactory) {
        nioConfig.setChannelReaderFactory(channelReaderFactory);
        return self();
    }

    public E bufferSize(int buffSize) {
        nioConfig.setReadBufferSize(buffSize);
        nioConfig.setWriteBufferSize(buffSize);
        return self();
    }

    public E workerNum(int workerNum) {
        nioConfig.setWorkerNum(workerNum);
        return self();
    }

    // ==================== SSL Configuration ====================

    public E ssl(boolean ssl) {
        this.ssl = ssl;
        return self();
    }

    protected boolean isSsl() {
        return ssl;
    }

    public E sslContext(SSLContext sslCtx) {
        this.sslCtx = Objects.requireNonNull(sslCtx, "sslCtx");
        this.ssl = true;
        return self();
    }

    public E sslContextFactory(SSLContextFactory sslContextFactory) {
        this.sslContextFactory = Objects.requireNonNull(sslContextFactory, "sslContextFactory");
        this.ssl = true;
        return self();
    }

    public E sslCipherSuites(String... sslCipherSuites) {
        this.sslCipherSuites = sslCipherSuites;
        return self();
    }

    public E applicationProtocols(String... applicationProtocols) {
        nioConfig.setApplicationProtocols(applicationProtocols);
        return self();
    }

    public E printSSLErrorLog(boolean bl) {
        nioConfig.setPrintSSLErrorLog(bl);
        return self();
    }

    public E printReadErrorLog(boolean bl) {
        nioConfig.setPrintReadErrorLog(bl);
        return self();
    }

    public E printApplicationMessage(boolean bl) {
        nioConfig.setPrintApplicationMessage(bl);
        return self();
    }

    public E sslHandshakeTimeout(long timeoutMs) {
        nioConfig.option(SocketOptions.SSL_HANDSHAKE_TIMEOUT_MS, timeoutMs);
        return self();
    }

    public E allowPlaintextWhenSslEnabled(boolean allow) {
        nioConfig.setAllowPlaintextWhenSslEnabled(allow);
        return self();
    }

    /** Set codec for reading (server-side). Override in TCPClient for write support. */
    public E channelCodec(ChannelCodec<?> codec) {
        nioConfig.setChannelReader(codec);
        return self();
    }

    public <T> E option(Option<T> option, T value) {
        nioConfig.option(option, value);
        return self();
    }

    // ==================== SSL helpers ====================

    SSLContext getSSLContext() {
        if (sslCtx != null) {
            return sslCtx;
        }
        if (sslContextFactory != null) {
            sslCtx = sslContextFactory.create();
            return sslCtx;
        }
        return null;
    }

    void initSslContext() {
        if (ssl && sslCtx == null && sslContextFactory == null) {
            throw new SocketException("SSL enabled but sslContext and sslContextFactory are both null");
        }
    }

    void checkServerAvailable() {
        if (shutdown) {
            throw new SocketException("Engine is shutdown");
        }
    }

    // ==================== Lifecycle ====================

    /**
     * Stop the service (can be restarted)
     */
    public synchronized void stop() {
        try {
            if (engineRunFlag) {
                engineRunFlag = false;
                nioConfig.clear();
                LOG.info("engine is stopped");
            }
        } catch (Throwable e) {
            throw e instanceof RuntimeException ? (RuntimeException) e : new SocketException(e.getMessage(), e);
        }
    }

    /**
     * Shutdown the service (note: the engine handle is no longer available)
     */
    public synchronized void shutdown() {
        if (engineRunFlag) {
            stop();
        }
        if (!shutdown) {
            Utils.shutdownExecutorService(executorService);
            if (runnerExecutor != null) {
                Utils.shutdownExecutorService(runnerExecutor);
            }
            shutdown = true;
        }
    }

    // ==================== Thread pool ====================

    ExecutorService createExecutor() {
        ExecutorService executorService;
        if (nioConfig.option(SocketOptions.ENABLE_VIRTUAL_THREAD)) {
            try {
                Method method = Executors.class.getMethod("newVirtualThreadPerTaskExecutor");
                method.setAccessible(true);
                executorService = (ExecutorService) method.invoke(null);
                if (executorService != null) {
                    LOG.info("virtual thread per-task executor enabled");
                    return executorService;
                }
            } catch (Throwable ignored) {
            }
        }
        int corePoolSize = Runtime.getRuntime().availableProcessors();
        int maxPoolSize = nioConfig.option(SocketOptions.MAX_CONCURRENT);
        if (maxPoolSize == -1) {
            LOG.warn("cached thread pool executor enabled (unbounded max concurrent)");
            return Executors.newCachedThreadPool(); // -1: unbounded
        }
        LOG.info("thread pool executor enabled (core={}, max={}, keepAlive=60s, syncQueue, callerRuns)",
                corePoolSize, maxPoolSize);
        return new ThreadPoolExecutor(
                corePoolSize,
                maxPoolSize,
                60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }
}

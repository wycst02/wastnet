package io.github.wycst.wastnet.http;

import io.github.wycst.wastnet.http.extension.HttpServerInterceptor;
import io.github.wycst.wastnet.http.extension.HttpServerObserver;
import io.github.wycst.wastnet.http.handler.HttpExceptionHandler;
import io.github.wycst.wastnet.http.handler.HttpRequestHandler;
import io.github.wycst.wastnet.http.handler.HttpServerChannelHandler;
import io.github.wycst.wastnet.http.reader.HttpChannelReaderFactory;
import io.github.wycst.wastnet.http.upgrade.UpgradeHandler;
import io.github.wycst.wastnet.socket.channel.ChannelReader;
import io.github.wycst.wastnet.socket.conf.Option;
import io.github.wycst.wastnet.socket.handler.ChannelHandler;
import io.github.wycst.wastnet.socket.handler.IdleStateHandler;
import io.github.wycst.wastnet.socket.tcp.ConnectionFilter;
import io.github.wycst.wastnet.socket.tcp.NioConfig;
import io.github.wycst.wastnet.socket.tcp.SSLContextFactory;
import io.github.wycst.wastnet.socket.tcp.TCPServer;

import javax.net.ssl.SSLContext;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * Base NIO HTTP server.
 *
 * <p>High-performance HTTP server based on NIO.</p>
 *
 * @author wangyc
 */
public class HTTPServer extends TCPServer {

    public static final String VERSION = resolveVersion();
    public static final String SERVER = "wastnet";

    private static String resolveVersion() {
        Package pkg = HTTPServer.class.getPackage();
        String ver = pkg != null ? pkg.getImplementationVersion() : null;
        return ver != null ? ver : "1.0.1";
    }

    final HttpServerChannelHandler serverChannelHandler;

    public HTTPServer(int port) {
        this(port, new NioConfig());
    }

    public HTTPServer(int port, NioConfig nioConfig) {
        super(port, nioConfig);
        super.channelReaderFactory(new HttpChannelReaderFactory());
        super.channelHandler(serverChannelHandler = new HttpServerChannelHandler());
    }

    public static HTTPServer of(int port) {
        return new HTTPServer(port);
    }

    public static HTTPServer of(int port, NioConfig nioConfig) {
        return new HTTPServer(port, nioConfig);
    }


    public HTTPServer ssl(boolean sslFlag) {
        super.ssl(sslFlag);
        return this;
    }

    public HTTPServer sslContext(SSLContext sslCtx) {
        super.sslContext(sslCtx);
        return this;
    }

    public HTTPServer sslContextFactory(SSLContextFactory sslContextFactory) {
        super.sslContextFactory(sslContextFactory);
        return this;
    }

    @Override
    public HTTPServer pemSSL(String certResource, String keyResource) {
        super.pemSSL(certResource, keyResource);
        return this;
    }

    @Override
    public HTTPServer pemSSL(InputStream certIn, InputStream keyIn) {
        super.pemSSL(certIn, keyIn);
        return this;
    }

    public HTTPServer sslCipherSuites(String... sslCipherSuites) {
        super.sslCipherSuites(sslCipherSuites);
        return this;
    }

    public HTTPServer applicationProtocols(String... applicationProtocols) {
        super.applicationProtocols(applicationProtocols);
        return this;
    }

    /**
     * Convenience method to enable HTTP/2 (h2) protocol via ALPN.
     * Equivalent to {@code .applicationProtocols("h2")}.
     *
     * @return this HTTPServer instance
     */
    public HTTPServer h2() {
        super.applicationProtocols("h2", "http/1.1");
        return this;
    }

    public HTTPServer printSSLErrorLog(boolean bl) {
        super.printSSLErrorLog(bl);
        return this;
    }

    public HTTPServer printReadErrorLog(boolean bl) {
        super.printReadErrorLog(bl);
        return this;
    }

    public HTTPServer printApplicationMessage(boolean bl) {
        super.printApplicationMessage(bl);
        return this;
    }

    public HTTPServer printStackTraceError(boolean printStackTraceError) {
        serverChannelHandler.setPrintStackTraceError(printStackTraceError);
        return this;
    }

    @SuppressWarnings("rawtypes")
    public HTTPServer channelReader(ChannelReader channelReader) {
        super.channelReader(channelReader);
        return this;
    }

    public HTTPServer idleStateHandler(IdleStateHandler idleStateHandler) {
        super.idleStateHandler(idleStateHandler);
        return this;
    }

    public HTTPServer connectionFilter(ConnectionFilter connectionFilter) {
        super.connectionFilter(connectionFilter);
        return this;
    }

    /**
     * Set whether the server only accepts local connections (bound to 127.0.0.1)
     *
     * @param localOnly true to bind to 127.0.0.1 only, false to bind to all interfaces
     * @return this HTTPServer instance
     */
    public HTTPServer localOnly(boolean localOnly) {
        super.localOnly(localOnly);
        return this;
    }

    public HTTPServer channelHandler(ChannelHandler<?> channelHandler) {
        // not allowed to change the built-in serverChannelHandler
        serverChannelHandler.setChildHandler(channelHandler);
        return this;
    }

    /**
     * Default request handler (path: "/**")
     *
     * @param requestHandler Request handler
     * @return this
     */
    public HTTPServer requestHandler(HttpRequestHandler requestHandler) {
        serverChannelHandler.setRequestHandler(requestHandler);
        return this;
    }

    /**
     * Used to support websocket or h2c
     *
     * @param upgradeHandler Upgrade handler
     * @return HTTPServer instance
     */
    public HTTPServer upgradeHandler(UpgradeHandler upgradeHandler) {
        serverChannelHandler.setUpgradeHandler(upgradeHandler);
        return this;
    }

    public HTTPServer bufferSize(int buffSize) {
        super.bufferSize(buffSize);
        return this;
    }

    /**
     * Set exception handler
     * When custom requestHandler throws exception during request processing, this exception handler will be invoked
     *
     * @param exceptionHandler Exception handler
     * @return HTTPServer instance
     */
    public HTTPServer exceptionHandler(HttpExceptionHandler exceptionHandler) {
        serverChannelHandler.setExceptionHandler(exceptionHandler);
        return this;
    }

    /**
     * Set the unified server interceptor for request/connection lifecycle
     * observation and an active pre-handle cut-in point. Pass {@code null} to disable.
     *
     * @param serverInterceptor the server interceptor
     * @return this HTTPServer instance
     */
    public HTTPServer interceptor(HttpServerInterceptor serverInterceptor) {
        serverChannelHandler.setInterceptor(serverInterceptor);
        return this;
    }

    /**
     * Set a passive server observer for request/connection lifecycle events
     * (metrics, tracing, audit logging, etc.). Pass {@code null} to disable.
     *
     * @param serverObserver the server observer
     * @return this HTTPServer instance
     */
    public HTTPServer observer(HttpServerObserver serverObserver) {
        serverChannelHandler.setObserver(serverObserver);
        return this;
    }

    public HTTPServer workerNum(int workerNum) {
        super.workerNum(workerNum);
        return this;
    }

    public <T> HTTPServer option(Option<T> option, T value) {
        super.option(option, value);
        return this;
    }

    private boolean startupBannerEnabled = true;
    private long startMillis;

    /**
     * Enable or disable the startup banner (default: enabled).
     */
    public HTTPServer startupBannerEnabled(boolean enabled) {
        this.startupBannerEnabled = enabled;
        return this;
    }

    public HTTPServer start() {
        this.startMillis = System.currentTimeMillis();
        serverChannelHandler.prepare();
        super.start();
        return this;
    }

    @Override
    protected void onStarted() {
        if (!startupBannerEnabled) return;
        long elapsed = System.currentTimeMillis() - startMillis;
        String scheme = isSsl() ? "https" : "http";
        String host = localOnly ? "127.0.0.1" : "localhost";
        System.out.println("  \u001B[36m" + SERVER + "/" + VERSION + "\u001B[0m started in " + elapsed + " ms");
        System.out.println("  >>  Local:   " + scheme + "://" + host + ":" + port);
        if (!localOnly) {
            logNetworkAddresses(scheme);
        }
    }

    @Override
    protected void onStopped() {
        if (!startupBannerEnabled) return;
        System.out.println("  \u001B[36m" + SERVER + "/" + VERSION + "\u001B[0m stopped");
    }

    private void logNetworkAddresses(String scheme) {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address) {
                        System.out.println("  >>  Network:" + " " + scheme + "://" + addr.getHostAddress() + ":" + port);
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to enumerate network interfaces: {}", e.getMessage());
        }
    }

    public UpgradeHandler upgradeHandler() {
        return serverChannelHandler.getUpgradeHandler();
    }
}

package io.github.wycst.wastnet.socket.tcp;

import io.github.wycst.wastnet.log.Log;
import io.github.wycst.wastnet.log.LogFactory;
import io.github.wycst.wastnet.util.Utils;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

/**
 * SSL channel runner that handles SSL handshake and encrypted data processing.
 */
class ChannelSSLRunner extends ChannelRunner {
    static Log LOG = LogFactory.getLog(ChannelSSLRunner.class);
    boolean isSSL;
    boolean finishHandshake;
    SSLEngineContext sslEngineContext;

    ChannelSSLRunner(ChannelWorker worker, SocketChannel channel, NioConfig nioConfig, SSLContext sslCtx, String[] sslCipherSuites) throws IOException {
        this(worker, new ChannelSSLContext(channel, new SSLEngineContext(sslCtx, sslCipherSuites, nioConfig.getApplicationProtocols(), nioConfig.getEnabledProtocols(), false)), nioConfig);
    }

    /**
     * Constructor accepting a pre-built SSL channel context (supports client-mode SSL).
     */
    ChannelSSLRunner(ChannelWorker worker, ChannelSSLContext channelCtx, NioConfig nioConfig) throws IOException {
        super(worker, channelCtx, nioConfig);
    }

    /**
     * Create a client-mode runner whose handshake is completed synchronously before
     * returning: a TLS client must send ClientHello first, which an OP_READ-only
     * registration could never trigger. The returned runner is ready for app data.
     */
    static ChannelSSLRunner createClientRunner(ChannelWorker worker, SocketChannel channel,
                                               NioConfig nioConfig, SSLContext sslCtx,
                                               String[] sslCipherSuites) throws Exception {
        SSLEngineContext engineCtx = new SSLEngineContext(sslCtx, sslCipherSuites,
                nioConfig.getApplicationProtocols(), nioConfig.getEnabledProtocols(), true);
        ChannelSSLContext channelCtx = ChannelSSLContext.createClientContext(
                Utils.id(), channel, engineCtx);
        ChannelSSLRunner runner = new ChannelSSLRunner(worker, channelCtx, nioConfig);
        runner.isSSL = true;
        runner.finishHandshake = true;
        runner.sslEngineContext = engineCtx;
        return runner;
    }

    @Override
    protected boolean beforeReady() {
        if (!finishHandshake) {
            ChannelSSLContext sslChannelContext = (ChannelSSLContext) ctx;
            this.sslEngineContext = sslChannelContext.sslEngineCtx;
            int handShakeFlag = sslHandShake(sslEngineContext);
            if (handShakeFlag == -1) {
                LOG.debug("SSL handshake failed");
                try {
                    release();
                } catch (IOException ignored) {
                    LOG.debug("release after handshake failure");
                }
                return false;
            }
            if (handShakeFlag == 2) return false;
            this.isSSL = handShakeFlag == 1;
            this.finishHandshake = true;
        }
        return true;
    }

    @Override
    protected int handleChannelRead() throws IOException {
        if (isSSL) {
            ChannelSSLContext sslCtx = (ChannelSSLContext) ctx;
            SSLEngine sslEngine = sslEngineContext.sslEngine;
            ByteBuffer packetInBuf = sslEngineContext.packetInBuf;
            ByteBuffer applicationInBuf = sslEngineContext.applicationInBuf;
            // may carry residual bytes (e.g. app data after the last flight): append, never clear()
            int bytesNum = sslCtx.channelRead(packetInBuf);
            if (bytesNum == -1) {
                return -1;
            } else {
                packetInBuf.flip();
                SSLEngineResult res;
                // compact + discard stale readFullyRemaining flag (set by outer readFully e.g. H2 preface)
                if (sslCtx.getAndResetRemaining()) {
                    applicationInBuf.compact();
                }
                do {
                    res = sslEngine.unwrap(packetInBuf, applicationInBuf);
                } while (res.getStatus() == SSLEngineResult.Status.OK);
                packetInBuf.compact();
                applicationInBuf.flip();
                do {
                    read(applicationInBuf);
                } while (sslCtx.getAndResetRemaining());
                applicationInBuf.clear();
                return 1;
            }
        } else {
            return super.handleChannelRead();
        }
    }

    /**
     * Check if it might be plaintext message
     * <p>
     * Each SSL handshake message starts with a type attribute of one byte,
     * and a length attribute of three bytes indicating the message length.
     * The first five bytes of an SSL record are the header. The first byte of the header is 22, indicating the handshake protocol, the next two bytes are the version, and the last two bytes are the remaining length of the entire SSL record
     *
     * <p> Here only checking if the packet starts with 22 can basically filter out all non-SSL data
     *
     * @param buf buf
     * @return true: possibly plaintext message, false: definitely not plaintext message
     */
    boolean isMaybePlaintext(ByteBuffer buf) {
        return buf.array()[0] != 22;
    }

    /**
     * SSL handshake
     *
     * @param sslEngineContext sslEngineContext
     * @return 0: not finish handshake (plain text), 1: finish handshake, 2: suspended waiting for peer data, -1: close/error
     */
    private int sslHandShake(SSLEngineContext sslEngineContext) {
        try {
            ChannelSSLContext sslChannelContext = (ChannelSSLContext) ctx;
            ByteBuffer packetInBuf = sslEngineContext.packetInBuf;
            // Pre-read + plaintext detection run only once; later OP_READ re-enters drive the engine directly.
            if (!sslChannelContext.handshakeStarted) {
                int size = channelReadInternal(packetInBuf);
                if (size < 1) return size == 0 ? 2 : -1;
                if (nioConfig.isAllowPlaintextWhenSslEnabled() && isMaybePlaintext(packetInBuf)) {
                    channelReader.init(ctx);
                    ready = true; // reader initialized here; before() must not init again
                    sslEngineContext.setDisabled(true);
                    int capacity = packetInBuf.capacity();
                    do {
                        packetInBuf.flip();
                        read(packetInBuf);
                        packetInBuf.clear();
                        if (size < capacity) {
                            return 0;
                        }
                        if ((size = channelReadInternal(packetInBuf)) < 1) return size;
                    } while (true);
                }
            }
            // Proceed with SSL handshake (packetInBuf may carry pre-read/residual data)
            return sslChannelContext.doHandshake(false) ? 1 : 2;
        } catch (Throwable e) {
            if (nioConfig.isPrintSSLErrorLog()) {
                LOG.error("SSL handshake failed", e);
            }
            return -1;
        }
    }
}

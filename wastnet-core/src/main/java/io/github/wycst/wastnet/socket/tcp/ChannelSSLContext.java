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

import io.github.wycst.wastnet.env.RuntimeEnv;
import io.github.wycst.wastnet.exception.SocketException;
import io.github.wycst.wastnet.socket.conf.SocketOptions;
import io.github.wycst.wastnet.util.Utils;

import javax.net.ssl.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.security.cert.X509Certificate;
import java.util.Map;

/**
 * SSL channel context that extends ChannelContext with SSL/TLS support.
 */
public final class ChannelSSLContext extends ChannelContext {
    final SSLEngineContext sslEngineCtx;
    boolean readFullyRemaining;
    boolean handshakeStarted;
    long handshakeDeadline;

    /**
     * Atomically read and clear the readFullyRemaining flag.
     * Used by {@link ChannelSSLRunner} to determine whether {@link #readFully}
     * left unconsumed data in applicationInBuf after a decode cycle.
     */
    boolean getAndResetRemaining() {
        boolean v = readFullyRemaining;
        readFullyRemaining = false;
        return v;
    }

    /**
     * Constructor for SSL channel context
     *
     * @param channel      the socket channel
     * @param sslEngineCtx the SSL engine context
     * @throws IOException if initialization fails
     */
    public ChannelSSLContext(SocketChannel channel, SSLEngineContext sslEngineCtx) throws IOException {
        this(Utils.id(), channel, sslEngineCtx);
    }

    /**
     * Constructor for SSL channel context
     *
     * @param id           the channel id
     * @param channel      the socket channel
     * @param sslEngineCtx the SSL engine context
     * @throws IOException if initialization fails
     */
    public ChannelSSLContext(long id, SocketChannel channel, SSLEngineContext sslEngineCtx) throws IOException {
        super(id, channel, sslEngineCtx.getSSLEngine().getSession().getApplicationBufferSize());
        this.sslEngineCtx = sslEngineCtx;
    }

    /**
     * Fill TLS-layer diagnostic data into the given map (monitoring only).
     * Exposes buffered-record and application-buffer occupancy so a stuck
     * read loop can be distinguished from a normal pending read.
     */
    public void fillTlsDiagnostic(Map<String, Object> diagnostics) {
        SSLEngineContext engineCtx = sslEngineCtx;
        diagnostics.put("ssl", !engineCtx.isDisabled());
        diagnostics.put("packetInBufRemaining", engineCtx.packetInBuf.remaining());
        diagnostics.put("applicationInBufRemaining", engineCtx.applicationInBuf.remaining());
        diagnostics.put("readFullyRemaining", readFullyRemaining);
        // Connection-level liveness (key signals for leak detection)
        diagnostics.put("closed", closeResolved.get());
        diagnostics.put("socketOpen", channel.isOpen());
    }

    static final TrustManager[] TRUST_ALL_MANAGERS = new TrustManager[]{
            new X509TrustManager() {
                @Override
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                @Override
                public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                @Override
                public void checkServerTrusted(X509Certificate[] certs, String authType) {}
            }
    };

    /**
     * Get the all-trusting TrustManager array for development/testing use.
     *
     * @return a copy of the TRUST_ALL_MANAGERS array
     */
    public static TrustManager[] getTrustAllManagers() {
        return new TrustManager[]{TRUST_ALL_MANAGERS[0]};
    }

    /**
     * Creates a client-side SSL channel context and performs the SSL handshake.
     * <p>
     * Uses the all-trusting TrustManager ({@link #TRUST_ALL_MANAGERS}) for convenience.
     * Supports ALPN application protocol negotiation when protocols are provided.
     *
     * @param id                 the channel id
     * @param channel            the connected socket channel
     * @param applicationProtocols the ALPN application protocols, may be null
     * @return the SSL channel context after successful handshake
     * @throws IOException if handshake fails
     */
    public static ChannelSSLContext createClientContext(long id, SocketChannel channel, String[] applicationProtocols) throws IOException {
        return createClientContext(id, channel, applicationProtocols, null);
    }

    public static ChannelSSLContext createClientContext(long id, SocketChannel channel, String[] applicationProtocols, String[] sslProtocols) throws IOException {
        return createClientContext(id, channel, applicationProtocols, sslProtocols, null);
    }

    /**
     * Creates a client-side SSL channel context and performs the SSL handshake.
     * <p>
     * When {@code trustManagers} is {@code null}, defaults to {@link #TRUST_ALL_MANAGERS}
     * (accepts all certificates, convenient for development/testing).
     * Pass a properly configured TrustManager for production use.
     * Supports ALPN application protocol negotiation when protocols are provided.
     *
     * @param id                 the channel id
     * @param channel            the connected socket channel
     * @param applicationProtocols the ALPN application protocols, may be null
     * @param trustManagers      the TrustManagers for certificate validation, or null for all-trusting default
     * @return the SSL channel context after successful handshake
     * @throws IOException if handshake fails
     */
    public static ChannelSSLContext createClientContext(long id, SocketChannel channel, String[] applicationProtocols, String[] sslProtocols, TrustManager[] trustManagers) throws IOException {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            if (trustManagers == null) {
                trustManagers = TRUST_ALL_MANAGERS;
            }
            sslContext.init(null, trustManagers, null);
            return createClientContext(id, channel, new SSLEngineContext(sslContext, null, applicationProtocols, sslProtocols, true));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to create client SSL context", e);
        }
    }

    /**
     * Creates a client-side SSL channel context from a pre-configured SSLEngineContext.
     * <p>
     * Callers can fully control the SSL configuration
     * (e.g. hostname verification, cipher suites, enabled protocols) by
     * setting up the {@link SSLEngineContext} themselves before passing it in.
     *
     * @param id          the channel id
     * @param channel     the connected socket channel
     * @param sslEngineCtx the pre-configured SSL engine context
     * @return the SSL channel context after successful handshake
     * @throws IOException if handshake fails
     */
    public static ChannelSSLContext createClientContext(long id, SocketChannel channel, SSLEngineContext sslEngineCtx) throws IOException {
        ChannelSSLContext ctx = new ChannelSSLContext(id, channel, sslEngineCtx);
        ctx.doHandshake(true);
        return ctx;
    }

    /**
     * Get the SSL handshake deadline in milliseconds.
     * Resolved from the connection-level NioConfig (SocketOptions.SSL_HANDSHAKE_TIMEOUT_MS),
     * falling back to the option's default when no NioConfig is bound.
     *
     * @return the SSL handshake deadline in milliseconds
     */
    long getSSLHandshakeDeadline() {
        long handshakeTimeoutMs = nioConfig != null
                ? nioConfig.option(SocketOptions.SSL_HANDSHAKE_TIMEOUT_MS)
                : SocketOptions.SSL_HANDSHAKE_TIMEOUT_MS.value;
        return handshakeTimeoutMs > 0 ? System.currentTimeMillis() + handshakeTimeoutMs : Long.MAX_VALUE;
    }

    // Lazily resolved absolute handshake deadline, shared across event-driven re-entries.
    long handshakeDeadline() {
        if (handshakeDeadline == 0) handshakeDeadline = getSSLHandshakeDeadline();
        return handshakeDeadline;
    }

    /**
     * Perform or continue the SSL handshake (re-entrant across OP_READ events).
     * Bytes already buffered in packetInBuf (e.g. pre-read by the caller) are consumed first.
     *
     * @param blocking true: spin-retry until finished or deadline (client user thread);
     *                 false: suspend as soon as the engine needs more peer data — nothing read,
     *                 or BUFFER_UNDERFLOW on a partial record — and resume on next OP_READ (server event loop)
     * @return true if handshake finished; false if suspended (blocking=false only)
     * @throws IOException if handshake fails or the absolute deadline expires
     */
    boolean doHandshake(boolean blocking) throws IOException {
        SSLEngine sslEngine = sslEngineCtx.sslEngine;
        ByteBuffer packetInBuf = sslEngineCtx.packetInBuf;
        ByteBuffer applicationInBuf = sslEngineCtx.applicationInBuf;
        ByteBuffer packetOutBuf = sslEngineCtx.packetOutBuf;
        ByteBuffer applicationOutBuf = sslEngineCtx.applicationOutBuf;

        // beginHandshake() is one-shot: later OP_READ re-entries resume the existing engine state
        if (!handshakeStarted) {
            sslEngine.beginHandshake();
            handshakeStarted = true;
        }
        SSLEngineResult.HandshakeStatus handshakeStatus = sslEngine.getHandshakeStatus();
        SSLEngineResult res;

        long deadline = handshakeDeadline();
        while (handshakeStatus != SSLEngineResult.HandshakeStatus.FINISHED && handshakeStatus != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
            if (System.currentTimeMillis() > deadline) {
                throw new SSLException("SSL handshake timeout");
            }
            switch (handshakeStatus) {
                case NEED_UNWRAP:
                    if (channelRead(packetInBuf) == -1) {
                        throw new SSLException("SSL handshake: connection closed");
                    }
                    // nothing read and no residual record (buffer stays in write mode): suspend until next OP_READ
                    if (!blocking && packetInBuf.position() == 0) return false;
                    packetInBuf.flip();
                    res = sslEngine.unwrap(packetInBuf, applicationInBuf);
                    packetInBuf.compact();
                    if (res.getStatus() == SSLEngineResult.Status.CLOSED) {
                        throw new SSLException("SSL handshake closed during unwrap");
                    }
                    handshakeStatus = res.getHandshakeStatus();
                    // partial record buffered: no progress until more bytes arrive
                    if (!blocking && res.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) return false;
                    break;

                case NEED_WRAP:
                    packetOutBuf.clear();
                    res = sslEngine.wrap(applicationOutBuf, packetOutBuf);
                    packetOutBuf.flip();
                    channelWrite(packetOutBuf);
                    if (res.getStatus() == SSLEngineResult.Status.CLOSED) {
                        throw new SSLException("SSL handshake closed during wrap");
                    }
                    handshakeStatus = res.getHandshakeStatus();
                    break;

                case NEED_TASK:
                    Runnable task;
                    while ((task = sslEngine.getDelegatedTask()) != null) {
                        task.run();
                    }
                    handshakeStatus = sslEngine.getHandshakeStatus();
                    break;
            }
        }
        packetOutBuf.clear();
        return true;
    }

    @Override
    public int getWriteBufferSize() {
        if (sslEngineCtx.isDisabled()) {
            return super.getWriteBufferSize();
        } else {
            return sslEngineCtx.packetOutBuf.capacity();
        }
    }

//    /**
//     * Write data with SSL encryption
//     *
//     * @param buf the buffer containing data to write
//     * @return the number of bytes written, or -1 if the SSL engine is closed
//     * @throws IOException if write operation fails
//     */
//    @Override
//    public int write(ByteBuffer buf) throws IOException {
//        final int remaining = buf.remaining();
//        if (sslEngineCtx.isDisabled()) {
//            channelWrite(buf);
//            buf.clear();
//        } else {
//            SSLEngine sslEngine = sslEngineCtx.sslEngine;
//            ByteBuffer packetOutBuf = sslEngineCtx.packetOutBuf;
//            // Loop processing until all data is encrypted
//            while (buf.hasRemaining()) {
//                SSLEngineResult res = sslEngine.wrap(buf, packetOutBuf);
//                SSLEngineResult.Status status = res.getStatus();
//                if (!packetOutBuf.hasRemaining() || status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
//                    flush();
//                    continue;
//                }
//                if (status != SSLEngineResult.Status.OK) {
//                    if (status == SSLEngineResult.Status.CLOSED) return -1;
//                    throw new SocketException("Unexpected exception, SSL encryption failed status: " + status);
//                }
//            }
//        }
//        return remaining;
//    }

    /**
     * Flush SSL encrypted data to channel
     *
     * @throws IOException if flush operation fails
     */
    @Override
    public void flush() throws IOException {
        if (sslEngineCtx.isDisabled()) {
            super.flush();
        } else {
            byteBuffer.flip();
            writeDirectToChannel(byteBuffer);
        }
    }

    /**
     * Encrypt the given plaintext buffer via SSL and write the resulting TLS
     * records directly to the channel (large-data and flush paths).
     *
     * @param buf the plaintext to encrypt and send (in read mode)
     * @return the number of plaintext bytes consumed
     * @throws IOException if encryption or write fails
     */
    @Override
    protected int writeDirectToChannel(ByteBuffer buf) throws IOException {
        if (sslEngineCtx.isDisabled()) {
            int n = channelWrite(buf);
            buf.clear();
            return n;
        }
        int len = buf.remaining();
        SSLEngine sslEngine = sslEngineCtx.sslEngine;
        ByteBuffer packetOutBuf = sslEngineCtx.packetOutBuf;
        // Encrypt all buffered plaintext; finally flushes the trailing record and resets buf on every exit.
        try {
            while (buf.hasRemaining()) {
                SSLEngineResult res = sslEngine.wrap(buf, packetOutBuf);
                SSLEngineResult.Status status = res.getStatus();
                if (!packetOutBuf.hasRemaining() || status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                    flushBuffer(packetOutBuf);
                    continue;
                }
                if (status != SSLEngineResult.Status.OK) {
                    if (status == SSLEngineResult.Status.CLOSED) return len - buf.remaining();
                    throw new SocketException("Unexpected exception, SSL encryption failed status: " + status);
                }
            }
            return len;
        } finally {
            flushBuffer(packetOutBuf);
            buf.clear();
        }
    }

    /**
     * Read decrypted data from SSL channel into buffer (non-blocking).
     * First consumes any remaining data in applicationInBuf, then
     * reads and unwraps network data from the channel.
     *
     * @param buf the buffer to read into
     * @return the number of bytes read, 0 if no data available, -1 if channel is closed
     * @throws IOException if read fails
     */
    @Override
    public int read(ByteBuffer buf) throws IOException {
        if (sslEngineCtx.isDisabled()) {
            return channelRead(buf);
        }
        SSLEngine sslEngine = sslEngineCtx.sslEngine;
        ByteBuffer packetInBuf = sslEngineCtx.packetInBuf;
        ByteBuffer applicationInBuf = sslEngineCtx.applicationInBuf;
        int totalRead = 0;

        // First, consume any remaining data in applicationInBuf
        if (applicationInBuf.position() > 0) {
            // Always in read mode; transferTo will read from current position
            totalRead += transferTo(applicationInBuf, buf);
            readFullyRemaining = applicationInBuf.hasRemaining();
            if (readFullyRemaining) {
                // Leave leftover in read mode for next call
                return totalRead;
            }
            applicationInBuf.clear();
        }

        // Read and unwrap network data
        int n = channelRead(packetInBuf);
        if (packetInBuf.position() == 0 && n < 1) {
            return totalRead > 0 ? totalRead : n;
        }

        packetInBuf.flip();
        try {
            SSLEngineResult res;
            do {
                res = sslEngine.unwrap(packetInBuf, applicationInBuf);
            } while (res.getStatus() == SSLEngineResult.Status.OK);

            if (res.getStatus() == SSLEngineResult.Status.CLOSED) {
                return totalRead > 0 ? totalRead : -1;
            }

            applicationInBuf.flip();
            totalRead += transferTo(applicationInBuf, buf);
            // Keep leftover in read mode so that the cache branch above
            if (!(readFullyRemaining = applicationInBuf.hasRemaining())) {
                applicationInBuf.clear();
            }
        } finally {
            packetInBuf.compact();
        }
        return totalRead;
    }

    /**
     * Transfer data from src ByteBuffer to dst ByteBuffer (partial transfer supported)
     *
     * @param src source buffer (must be in read mode)
     * @param dst destination buffer
     * @return number of bytes transferred
     */
    int transferTo(ByteBuffer src, ByteBuffer dst) {
        if (!src.hasRemaining()) return 0;
        int toTransfer = Math.min(src.remaining(), dst.remaining());
        int oldLimit = src.limit();
        src.limit(src.position() + toTransfer);
        dst.put(src);
        src.limit(oldLimit);
        return toTransfer;
    }

    /**
     * Blocking read of SSL decrypted data until the specified length is filled
     *
     * @param b         target byte array
     * @param off       array starting offset
     * @param len       number of bytes to read
     * @param timeoutMs timeout in milliseconds
     * @return number of bytes successfully read, or -1 indicating end of stream
     * @throws IOException if read operation fails
     */
    @Override
    public int readFully(byte[] b, int off, final int len, long timeoutMs) throws IOException {
        if (sslEngineCtx.isDisabled()) {
            return super.readFully(b, off, len, timeoutMs);
        } else {
            long startTime = System.currentTimeMillis();
            ByteBuffer applicationInBuf = sslEngineCtx.applicationInBuf;
            int limit = applicationInBuf.limit();
            int position = applicationInBuf.position();
            int tlen = len;
            if (position > 0) {
                // just check if not the first and read
                int rem = limit - position;   // applicationInBuf.remaining()
                if (rem >= len) {
                    readFullyRemaining = applicationInBuf.get(b, off, len).hasRemaining();
                    return len;
                }
                // read all buffered data
                applicationInBuf.get(b, off, rem);
                off += rem;
                tlen -= rem;
                // switch to write mode
                applicationInBuf.clear();
            }
            SSLEngine sslEngine = sslEngineCtx.sslEngine;
            ByteBuffer packetInBuf = sslEngineCtx.packetInBuf;
            // packetInBuf.clear(); // do not call clear()
            while (true) {
                int bytesNum;
                // Inner loop: handle bytesNum=0 by blocking until data arrives
                while (true) {
                    bytesNum = channelRead(packetInBuf);
                    if (bytesNum == -1) {
                        return -1;
                    } else if (bytesNum > 0) {
                        break; // Exit inner loop when data is available
                    } else {
                        if(packetInBuf.position() == 0) {
                            // bytesNum == 0 and empty packetInBuf: wait for data
                            awaitReadableWithTimeout(startTime, timeoutMs);
                        } else break;
                    }
                }
                packetInBuf.flip();
                SSLEngineResult res;
                do {
                    res = sslEngine.unwrap(packetInBuf, applicationInBuf);
                } while (res.getStatus() == SSLEngineResult.Status.OK);

                packetInBuf.compact();
                applicationInBuf.flip();
                // position is zero after flip
                limit = applicationInBuf.limit();
                if (limit >= tlen) {
                    readFullyRemaining = applicationInBuf.get(b, off, tlen).hasRemaining();
                    return len;
                } else {
                    applicationInBuf.get(b, off, limit);
                    off += limit;
                    tlen -= limit;
                    // switch to write mode
                    applicationInBuf.clear();
                }

                // Check unwrap result status after processing data
                SSLEngineResult.Status status = res.getStatus();
                if (status == SSLEngineResult.Status.CLOSED) {
                    return -1;
                }
                /*if (status != SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                    throw new SSLException("SSL unwrap failed with unexpected status: " + status);
                }*/
            }
        }
    }

    /**
     * Check if this is an SSL channel
     *
     * @return true if SSL is enabled, false otherwise
     */
    @Override
    public boolean isSSL() {
        return !sslEngineCtx.isDisabled();
    }

    /**
     * Get the handshaked application protocol
     *
     * @return the application protocol, or null if not available
     */
    @Override
    public String getHandShakedApplicationProtocol() {
        if (isSSL()) {
            return RuntimeEnv.INSTANCE.getSSLApplicationProtocol(sslEngineCtx.sslEngine);
        } else {
            return null;
        }
    }

    /**
     * Sends the close_notify alert before the socket teardown; invoked once by the parent's close CAS.
     */
    @Override
    protected void closeBefore() {
        if (sslEngineCtx.isDisabled()) return;
        try {
            SSLEngine sslEngine = sslEngineCtx.sslEngine;
            sslEngine.closeOutbound();
            // wrap() produces one encrypted close_notify record; measured max 85B on JDK 21 (TLS1.2 AES256-CBC/SHA384), 256 is 3x headroom
            ByteBuffer alert = ByteBuffer.allocate(256);
            sslEngine.wrap(EMPTY_BUFFER, alert);
            alert.flip();
            // Blocking complete write so the close_notify is fully delivered (bounded by writeTimeoutMs, never partial)
            channelWrite(alert);
        } catch (Throwable ignored) {
        }
    }
}

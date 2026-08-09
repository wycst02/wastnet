package io.github.wycst.wastnet.socket.tcp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;

/**
 * Lightweight TCP proxy — direct SocketChannel relay without codec/handler/ChannelContext.
 * <p>
 * Registers both sockets of each connection pair on a shared Selector.
 * Each key's attachment points to the peer channel; a {@link PendingWrite}
 * wrapper replaces it temporarily during partial writes.
 * <p>
 * Usage:
 * <pre>{@code
 * TcpProxy proxy = new TcpProxy(localPort, "remote-host", remotePort);
 * proxy.start();
 * }</pre>
 *
 * @author wangyc
 */
public class TcpProxy {

    private final int localPort;
    private final String remoteHost;
    private final int remotePort;
    private final ByteBuffer buffer = ByteBuffer.allocateDirect(16384);

    private ServerSocketChannel serverChannel;
    private Selector selector;
    private volatile boolean running;

    public TcpProxy(int localPort, String remoteHost, int remotePort) {
        this.localPort = localPort;
        this.remoteHost = remoteHost;
        this.remotePort = remotePort;
    }

    public synchronized void start() throws IOException {
        if (running) return;
        running = true;
        selector = Selector.open();
        serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.socket().setReuseAddress(true);
        serverChannel.bind(new InetSocketAddress(localPort));
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);
        new Thread(new Runnable() {
            public void run() {
                TcpProxy.this.run();
            }
        }, "tcp-proxy").start();
    }

    public synchronized void stop() throws IOException {
        running = false;
        if (selector != null) {
            selector.wakeup();
            selector.close();
            selector = null;
        }
        if (serverChannel != null) {
            serverChannel.close();
            serverChannel = null;
        }
    }

    private void run() {
        try {
            while (running && selector != null) {
                selector.select();
                Iterator<SelectionKey> iter = selector.selectedKeys().iterator();
                while (iter.hasNext()) {
                    SelectionKey key = iter.next();
                    iter.remove();
                    try {
                        if (!key.isValid()) continue;
                        if (key.isAcceptable()) {
                            doAccept();
                        } else if (key.isReadable()) {
                            doRead(key);
                        } else if (key.isWritable()) {
                            doWrite(key);
                        }
                    } catch (Exception e) {
                        closePair(key);
                    }
                }
            }
        } catch (IOException ignored) {
        }
    }

    private void doAccept() throws IOException {
        SocketChannel client = serverChannel.accept();
        client.configureBlocking(false);
        client.socket().setTcpNoDelay(true);

        SocketChannel target = SocketChannel.open();
        try {
            target.configureBlocking(true);
            target.socket().setTcpNoDelay(true);
            target.connect(new InetSocketAddress(remoteHost, remotePort));
            target.configureBlocking(false);
        } catch (IOException e) {
            try { client.close(); } catch (IOException ignored) {}
            try { target.close(); } catch (IOException ignored) {}
            return;
        }

        client.register(selector, SelectionKey.OP_READ, target);
        target.register(selector, SelectionKey.OP_READ, client);
    }

    @SuppressWarnings("unchecked")
    private void doRead(SelectionKey key) throws IOException {
        SocketChannel src = (SocketChannel) key.channel();

        // Resolve peer — attachment is either SocketChannel or PendingWrite
        Object att = key.attachment();
        SocketChannel dst = att instanceof SocketChannel ? (SocketChannel) att : ((PendingWrite) att).peer;

        int n = src.read(buffer);
        if (n < 0) {
            closePair(key);
            return;
        }
        buffer.flip();

        int written = dst.write(buffer);
        if (buffer.hasRemaining()) {
            // Partial write — save remaining, wait for OP_WRITE
            ByteBuffer pending = ByteBuffer.allocateDirect(buffer.remaining());
            pending.put(buffer);
            pending.flip();
            // Find dst's key and set pending write
            dst.keyFor(selector).attach(new PendingWrite(src, pending));
            dst.keyFor(selector).interestOps(SelectionKey.OP_WRITE);
            // Pause reading from src until pending write completes
            key.interestOps(0);
        } else if (written > 0 && att instanceof PendingWrite) {
            // All remaining data flushed — restore to normal read
            key.attach(dst);
            key.interestOps(SelectionKey.OP_READ);
        }
        buffer.clear();
    }

    @SuppressWarnings("unchecked")
    private void doWrite(SelectionKey key) throws IOException {
        PendingWrite pw = (PendingWrite) key.attachment();
        SocketChannel ch = (SocketChannel) key.channel();

        ch.write(pw.data);
        if (!pw.data.hasRemaining()) {
            // All pending data flushed — restore to read mode
            key.attach(pw.peer);
            key.interestOps(SelectionKey.OP_READ);
            // Resume reading from the peer
            pw.peer.keyFor(selector).interestOps(SelectionKey.OP_READ);
        }
    }

    private void closePair(SelectionKey key) {
        if (key.attachment() == null) return;
        SocketChannel src = (SocketChannel) key.channel();
        Object att = key.attachment();
        SocketChannel dst = att instanceof SocketChannel ? (SocketChannel) att : ((PendingWrite) att).peer;
        key.attach(null);
        key.cancel();
        try { src.close(); } catch (Exception ignored) {}
        try { dst.close(); } catch (Exception ignored) {}
    }

    /**
     * Holds the peer channel and pending bytes during a partial write.
     */
    static class PendingWrite {
        final SocketChannel peer;
        final ByteBuffer data;

        PendingWrite(SocketChannel peer, ByteBuffer data) {
            this.peer = peer;
            this.data = data;
        }
    }
}

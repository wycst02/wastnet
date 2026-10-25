package io.github.wycst.wastnet.socket.tcp;

import io.github.wycst.wastnet.log.Log;
import io.github.wycst.wastnet.log.LogFactory;
import io.github.wycst.wastnet.socket.conf.SocketOptions;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

/**
 * Connection accept dispatcher that runs in a dedicated thread.
 * Listens for accept events and distributes new connections to workers.
 */
class AcceptDispatcher extends Thread {

    static final Log LOG = LogFactory.getLog(AcceptDispatcher.class);

    /** Max connections drained from the backlog in one accept event */
    private static final int MAX_ACCEPTS_PER_EVENT = 256;

    private final TCPServer server;
    private final ChannelWorker[] workers;

    public AcceptDispatcher(TCPServer server, ChannelWorker[] workers) {
        this.server = server;
        this.workers = workers;
    }

    /** Close the client channel, logging a warning with the given reason on failure. */
    private static void closeQuietly(SocketChannel client, String reason) {
        try {
            client.close();
        } catch (IOException ex) {
            LOG.warn(reason, ex.getMessage());
        }
    }

    void handleAccept() throws Throwable {
        // Signal that AcceptDispatcher thread has entered its event loop
        CountDownLatch latch = server.startLatch;
        if (latch != null) latch.countDown();

        int clientCnt = 0;
        final Set<SelectionKey> selectedKeys = server.selector.selectedKeys();
        while (server.acceptRunFlag) {
            int num = server.selector.select();
            if (num == 0) continue;
            Iterator<SelectionKey> iter = selectedKeys.iterator();
            while (iter.hasNext()) {
                SelectionKey key = iter.next();
                iter.remove();
                if (!key.isValid()) {
                    LOG.info("accept key invalid, server channel likely closing");
                    continue;
                }
                int accepted = 0;
                while (accepted++ < MAX_ACCEPTS_PER_EVENT) {
                    final SocketChannel client;
                    try {
                        if ((client = server.serverChannel.accept()) == null) break;
                    } catch (IOException ex) {
                        LOG.warn("accept failed, stop draining this event: {}", ex.getMessage());
                        break;
                    }
                    try {
                        ConnectionFilter connectionFilter = server.nioConfig.getConnectionFilter();
                        if (connectionFilter != null && (!connectionFilter.onAccept(client) || !client.isOpen())) {
                            LOG.info("Connection rejected from {}", client.getRemoteAddress());
                            closeQuietly(client, "close rejected connection: {}");
                            continue;
                        }
                    } catch (Throwable e) {
                        LOG.error("Connection filter error: {}", e, e.getMessage());
                        closeQuietly(client, "close connection after filter error: {}");
                        continue;
                    }
                    client.configureBlocking(false);
                    client.socket().setTcpNoDelay(true);
                    client.socket().setKeepAlive(server.nioConfig.option(SocketOptions.TCP_KEEPALIVE));
                    final ChannelWorker worker = server.nextWorker(++clientCnt, workers);
                    server.executorService.submit(() -> {
                        try {
                            worker.register(client, server.createConnectionRunner(worker, client));
                        } catch (Exception e) {
                            LOG.error("Client registration failed: {}", e, e.getMessage());
                            closeQuietly(client, "close client on registration fail: {}");
                        }
                    });
                }
            }
        }
        for (ChannelWorker worker : workers) {
            worker.wakeup();
        }
    }

    @Override
    public void run() {
        try {
            handleAccept();
        } catch (Throwable e) {
            LOG.error("Accept dispatcher error: {}", e, e.getMessage());
        }
    }
}

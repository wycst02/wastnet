package io.github.wycst.wastnet.socket.tcp;

import io.github.wycst.wastnet.log.Log;
import io.github.wycst.wastnet.log.LogFactory;

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

    private final TCPServer server;
    private final ChannelWorker[] workers;

    public AcceptDispatcher(TCPServer server, ChannelWorker[] workers) {
        this.server = server;
        this.workers = workers;
    }

    void handleAccept() throws Throwable {
        // Signal that AcceptDispatcher thread has entered its event loop
        CountDownLatch latch = server.startLatch;
        if (latch != null) latch.countDown();

        int clientCnt = 0;
        while (server.acceptRunFlag) {
            int num = server.selector.select();
            if (num == 0) continue;
            Set<SelectionKey> selectedKeys = server.selector.selectedKeys();
            Iterator<SelectionKey> iter = selectedKeys.iterator();
            while (iter.hasNext()) {
                SelectionKey key = iter.next();
                iter.remove();
                if (!key.isValid()) {
                    LOG.info("isValid false");
                    continue;
                }
                if (key.isAcceptable()) {
                    final SocketChannel client = server.serverChannel.accept();
                    try {
                        ConnectionFilter connectionFilter = server.nioConfig.getConnectionFilter();
                        if (connectionFilter != null && (!connectionFilter.onAccept(client) || !client.isOpen())) {
                            LOG.info("Connection rejected from {}", client.getRemoteAddress());
                            try { client.close(); } catch (IOException ex) { LOG.warn("close rejected connection: {}", ex.getMessage()); }
                            continue;
                        }
                    } catch (Throwable e) {
                        LOG.error("Connection filter error: {}", e, e.getMessage());
                        try { client.close(); } catch (IOException ex) { LOG.warn("close connection after filter error: {}", ex.getMessage()); }
                        continue;
                    }
                    client.configureBlocking(false);
                    client.socket().setTcpNoDelay(true);
                    final ChannelWorker worker = server.nextWorker(++clientCnt, workers);
                    server.executorService.submit(() -> {
                        try {
                            worker.register(client, server.createConnectionRunner(worker, client));
                        } catch (Exception e) {
                            LOG.error("Client registration failed: {}", e, e.getMessage());
                            try {
                                client.close();
                            } catch (IOException ex) {
                                LOG.warn("close client on registration fail: {}", ex.getMessage());
                            }
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

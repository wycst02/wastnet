package io.github.wycst.wastnet.examples.http;

import io.github.wycst.wastnet.http.HTTPServer;
import io.github.wycst.wastnet.socket.handler.IdleStateHandler;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.util.concurrent.TimeUnit;

/**
 * IdleStateHandler usage example.
 * <p>
 * Starts an HTTP server with idle detection:
 * - Triggers every 10s when no read activity
 * - Logs consecutive idle count, closes connection after 3rd consecutive trigger (30s total)
 * <p>
 * Usage:
 * 1. Run this class
 * 2. Open http://localhost:8080 in browser, then leave it idle
 * 3. Observe console: first idle at 10s, warning at 20s, disconnect at 30s
 */
public class IdleStateHandlerExample {

    private static final int MAX_IDLE_BEFORE_CLOSE = 3;

    public static void main(String[] args) throws Exception {
        HTTPServer server = HTTPServer.of(8080)
                .idleStateHandler(new IdleStateHandler(10, 0, TimeUnit.SECONDS) {
                    @Override
                    public void onIdleTriggered(ChannelContext ctx, IdleStateHandler.IdleType idleType,
                                                 long triggerTotalCount, long triggerConsecutiveCount) {
                        System.out.println("[Idle] connection=" + ctx.getId()
                                + " type=" + idleType
                                + " total=" + triggerTotalCount
                                + " consecutive=" + triggerConsecutiveCount);

                        if (triggerConsecutiveCount >= MAX_IDLE_BEFORE_CLOSE) {
                            System.out.println("  -> closing idle connection");
                            ctx.close();
                        } else {
                            System.out.println("  -> " + (MAX_IDLE_BEFORE_CLOSE - triggerConsecutiveCount)
                                    + " more idle checks before close");
                        }
                    }
                })
                .requestHandler((request, response) -> {
                    response.contentType("text/plain;charset=utf-8")
                            .body("OK".getBytes());
                })
                .start();

        System.out.println("Server started, idle check every 10s, close after " + MAX_IDLE_BEFORE_CLOSE + " consecutive idle triggers.");
        System.out.println("Open http://localhost:8080 and leave it idle to see the progression.");
    }
}


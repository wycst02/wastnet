package io.github.wycst.wastnet.examples.tcp;

import io.github.wycst.wastnet.socket.handler.ChannelHandler;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import io.github.wycst.wastnet.socket.tcp.NioConfig;
import io.github.wycst.wastnet.socket.tcp.TCPServer;

import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TCP连接测试
 */
public class TcpConnectionTest {

    private static final int SERVER_PORT = 8080;
    private static final int TARGET_CONNECTIONS = 1000000; // 降低目标连接数以适应Windows限制
    private static final int CLIENT_THREADS = 10; // 减少线程数
    private static final int CONNECTIONS_PER_THREAD = TARGET_CONNECTIONS / CLIENT_THREADS;
    
    private static final AtomicLong connectedCount = new AtomicLong(0);
    private static final AtomicInteger activeConnections = new AtomicInteger(0);
    private static final AtomicLong failedCount = new AtomicLong(0);
    // 添加连接间隔控制
    private static final int CONNECT_INTERVAL_MS = 10;
    // 添加重试机制
    private static final int MAX_RETRY_ATTEMPTS = 3;



    public static void main(String[] args) throws Exception {
        System.out.println("=== TCP连接压力测试（Windows优化版）===");
        System.out.println("目标连接数: " + TARGET_CONNECTIONS);
        System.out.println("客户端线程数: " + CLIENT_THREADS);
        System.out.println("每线程连接数: " + CONNECTIONS_PER_THREAD);
        System.out.println("连接间隔: " + CONNECT_INTERVAL_MS + "ms");

        // 启动服务器
        startServer();

        // 执行测试
        long startTime = System.currentTimeMillis();
        testConnections();
        long endTime = System.currentTimeMillis();
        
        // 输出结果
        printResults(startTime, endTime);
        // server.shutdown();
    }

    private static TCPServer startServer() throws InterruptedException {
        NioConfig config = new NioConfig();
        config.setWorkerNum(31); // 减少工作线程数
        config.setReadBufferSize(512);
        config.setWriteBufferSize(512);
        
        TCPServer server = new TCPServer(SERVER_PORT, config);
        server.channelHandler(new EchoHandler());
        server.printApplicationMessage(false);
        server.printReadErrorLog(false);
        
        server.start();
        return server;
    }

    private static void testConnections() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(CLIENT_THREADS);
        CountDownLatch latch = new CountDownLatch(CLIENT_THREADS);
        
        for (int i = 0; i < CLIENT_THREADS; ++i) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    createConnections(threadId);
                } catch (Exception e) {
                    System.err.println("线程" + threadId + "错误: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await(300, TimeUnit.SECONDS); // 增加等待时间
        executor.shutdown();
    }

    private static void createConnections(int threadId) {
        SocketChannel[] channels = new SocketChannel[CONNECTIONS_PER_THREAD];
        
        try {
            // 建立连接
            for (int i = 0; i < CONNECTIONS_PER_THREAD; ++i) {
                SocketChannel channel = SocketChannel.open();
                channel.configureBlocking(false);
                channel.connect(new InetSocketAddress(SERVER_PORT));
                while (!channel.finishConnect()) {
                    Thread.yield();
                }
                
                channels[i] = channel;
                connectedCount.incrementAndGet();
                activeConnections.incrementAndGet();
                
                if ((i + 1) % 1000 == 0) {
                    System.out.println("线程" + threadId + ": 已连接 " + (i + 1));
                }

                long totalCount = connectedCount.get();
                if (totalCount % 1000 == 0) {
                    System.out.println("总已完成 " + totalCount + " 个连接");
                }
            }
            
            System.out.println("线程" + threadId + ": 成功建立 " + CONNECTIONS_PER_THREAD + " 个连接");
            
            // 保持连接30秒
            Thread.sleep(10000);
            
        } catch (Exception e) {
            System.err.println("连接错误: " + e.getMessage());
        } finally {
            // 关闭连接
            for (SocketChannel channel : channels) {
                if (channel != null) {
                    try {
//                        channel.close();
//                        activeConnections.decrementAndGet();
                    } catch (Exception e) {}
                }
            }
        }
    }

    private static void printResults(long startTime, long endTime) {
        long duration = endTime - startTime;
        long successCount = connectedCount.get();
        long failCount = failedCount.get();
        
        System.out.println("\n=== 测试结果 ===");
        System.out.println("测试耗时: " + duration + " ms");
        System.out.println("成功连接: " + successCount);
        System.out.println("失败连接: " + failCount);
        System.out.println("总计尝试: " + (successCount + failCount));
        System.out.println("连接速度: " + (successCount * 1000 / Math.max(duration, 1)) + " 连接/秒");
        System.out.println("成功率: " + String.format("%.2f%%", 
                successCount * 100.0 / Math.max(successCount + failCount, 1)));
        System.out.println("峰值活跃连接: " + activeConnections.get());
    }

    static class EchoHandler implements ChannelHandler<String> {
        @Override
        public void onConnected(ChannelContext ctx) {}
        
        @Override
        public void onHandle(ChannelContext ctx, String msg) {
            try {
                ctx.writeFlush(("Echo: " + msg).getBytes());
            } catch (Exception ignored) {}
        }
        
        @Override
        public void onClosed(ChannelContext ctx) {}
        
        @Override
        public void onException(ChannelContext ctx, Throwable throwable) {}
    }
}

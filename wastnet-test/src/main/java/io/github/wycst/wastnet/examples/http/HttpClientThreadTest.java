package io.github.wycst.wastnet.examples.http;

import io.github.wycst.wast.clients.http.HttpClient;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @since 2024-11-17
 * @author wangyc
 */
public class HttpClientThreadTest {
    private static final ExecutorService executorService = Executors.newFixedThreadPool(32);
    private static final HttpClient httpClient = new HttpClient();

    public static void main(String[] args) throws InterruptedException {

        final int size = 100000;
        final CountDownLatch countDownLatch = new CountDownLatch(size);

        for (int i = 0; i < size; ++i) {
            executorService.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        httpClient.get("http://localhost:8999/");
                    } finally {
                        countDownLatch.countDown();
                    }
                }
            });
        }
        countDownLatch.await();
        System.out.println("finish");
        System.exit(0);
    }
}

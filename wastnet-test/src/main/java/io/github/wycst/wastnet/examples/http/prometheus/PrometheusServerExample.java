package io.github.wycst.wastnet.examples.http.prometheus;

import io.github.wycst.wastnet.http.HTTPServer;
import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.http.handler.HttpRequestHandler;

import java.util.Map;

/**
 * Runnable example demonstrating how to plug {@link SimpleCounterServerObserver}
 * into an {@link HTTPServer} and expose its metrics via a {@code /metrics} endpoint.
 *
 * <p>Start the server and visit:
 * <ul>
 *   <li>http://localhost:8080/        &mdash; a normal request (counted by the observer)</li>
 *   <li>http://localhost:8080/metrics &mdash; current metrics snapshot</li>
 * </ul>
 */
public class PrometheusServerExample {

    public static void main(String[] args) throws Exception {
        final SimpleCounterServerObserver observer = new SimpleCounterServerObserver();

        int port = Integer.getInteger("port", 8080);

        HTTPServer.of(port)
                .observer(observer)
                .requestHandler(new HttpRequestHandler() {
                    @Override
                    public void handle(HttpRequest request, HttpResponse response) throws Throwable {
                        String uri = request.getRequestUri();
                        if ("/metrics".equals(uri)) {
                            response.contentType("text/plain;charset=utf-8")
                                   .body(formatMetrics(observer.getMetrics()));
                            return;
                        }
                        response.contentType("text/plain;charset=utf-8")
                               .body("hello world");
                    }
                })
                .start();

        System.out.println("Prometheus example server started on http://localhost:" + port);
        System.out.println("  normal request : http://localhost:" + port + "/");
        System.out.println("  metrics        : http://localhost:" + port + "/metrics");
    }

    private static String formatMetrics(Map<String, Object> metrics) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : metrics.entrySet()) {
            sb.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }
        return sb.toString();
    }
}

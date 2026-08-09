package io.github.wycst.wastnet.examples.http.prometheus;

import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.http.HttpStatus;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the application-layer {@link SimpleCounterServerObserver}
 * demo implementation of the {@link io.github.wycst.wastnet.http.extension.HttpServerObserver} SPI.
 */
public class SimpleCounterServerObserverTest {

    // ==================== SimpleCounterServerObserver ====================

    @Test
    public void testRequestLifecycleSuccess() {
        SimpleCounterServerObserver obs = new SimpleCounterServerObserver();
        HttpRequest req = mock(HttpRequest.class);
        HttpResponse resp = mock(HttpResponse.class);
        when(resp.getStatus()).thenReturn(HttpStatus.OK);

        obs.onRequestStart(req);
        obs.onRequestComplete(req, resp, 1_000_000L, null);

        Map<String, Object> m = obs.getMetrics();
        assertEquals(1L, m.get("totalRequests"));
        assertEquals(0L, m.get("activeRequests"));
        assertEquals(1L, m.get("success2xx"));
        assertEquals(0L, m.get("redirect3xx"));
        assertEquals(0L, m.get("clientError4xx"));
        assertEquals(0L, m.get("serverError5xx"));
        assertEquals(0L, m.get("errorRequests"));
        assertEquals(1_000_000L, m.get("totalDurationNanos"));
        assertEquals(1.0, (Double) m.get("avgDurationMs"), 0.0001);
        assertEquals(1.0, (Double) m.get("maxDurationMs"), 0.0001);
    }

    @Test
    public void testStatusBuckets() {
        SimpleCounterServerObserver obs = new SimpleCounterServerObserver();
        HttpRequest req = mock(HttpRequest.class);

        HttpResponse r301 = mock(HttpResponse.class);
        when(r301.getStatus()).thenReturn(HttpStatus.MOVED_PERMANENTLY);
        obs.onRequestStart(req);
        obs.onRequestComplete(req, r301, 1L, null);

        HttpResponse r404 = mock(HttpResponse.class);
        when(r404.getStatus()).thenReturn(HttpStatus.NOT_FOUND);
        obs.onRequestStart(req);
        obs.onRequestComplete(req, r404, 1L, null);

        HttpResponse r500 = mock(HttpResponse.class);
        when(r500.getStatus()).thenReturn(HttpStatus.INTERNAL_SERVER_ERROR);
        obs.onRequestStart(req);
        obs.onRequestComplete(req, r500, 1L, new RuntimeException("boom"));

        Map<String, Object> m = obs.getMetrics();
        assertEquals(1L, m.get("redirect3xx"));
        assertEquals(1L, m.get("clientError4xx"));
        assertEquals(1L, m.get("serverError5xx"));
        assertEquals(1L, m.get("errorRequests"));
    }

    @Test
    public void testMaxDurationTracking() {
        SimpleCounterServerObserver obs = new SimpleCounterServerObserver();
        HttpRequest req = mock(HttpRequest.class);
        HttpResponse resp = mock(HttpResponse.class);
        when(resp.getStatus()).thenReturn(HttpStatus.OK);

        obs.onRequestStart(req);
        obs.onRequestComplete(req, resp, 1_000_000L, null);
        obs.onRequestStart(req);
        obs.onRequestComplete(req, resp, 5_000_000L, null);
        obs.onRequestStart(req);
        obs.onRequestComplete(req, resp, 2_000_000L, null);

        Map<String, Object> m = obs.getMetrics();
        assertEquals(5.0, (Double) m.get("maxDurationMs"), 0.0001);
        assertEquals((1.0 + 5.0 + 2.0) / 3.0, (Double) m.get("avgDurationMs"), 0.0001);
    }

    @Test
    public void testConnectionLifecycle() {
        SimpleCounterServerObserver obs = new SimpleCounterServerObserver();
        ChannelContext ctx = mock(ChannelContext.class);
        obs.onConnectionOpen(ctx);
        obs.onConnectionOpen(ctx);
        Map<String, Object> m = obs.getMetrics();
        assertEquals(2L, m.get("totalConnections"));
        assertEquals(2L, m.get("activeConnections"));
        obs.onConnectionClose(ctx);
        assertEquals(1L, obs.getMetrics().get("activeConnections"));
    }
}

package io.github.wycst.wastnet.http.handler;

import io.github.wycst.wastnet.http.*;
import io.github.wycst.wastnet.http.extension.HttpServerInterceptor;
import io.github.wycst.wastnet.http.extension.HttpServerObserver;
import io.github.wycst.wastnet.examples.http.prometheus.SimpleCounterServerObserver;
import io.github.wycst.wastnet.socket.handler.ChannelHandler;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Test;

import java.nio.channels.SocketChannel;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * tests for the interceptor / observer instrumentation inside
 * {@link HttpServerChannelHandler}, dispatched through the internal
 * {@code HttpRequestLifecycleDelegate}, and the {@code HTTPServer#interceptor} /
 * {@code HTTPServer#observer} entry points.
 * <p>
 */
public class HttpServerChannelHandlerMetricsTest {

    private ChannelContext createCtx() throws Exception {
        SocketChannel ch = SocketChannel.open();
        ch.configureBlocking(false);
        ChannelContext ctx = new ChannelContext(ch, 4096);
        ctx.setChannelHandler(new ChannelHandler<Object>() {
            @Override
            public void onHandle(ChannelContext c, Object msg) {
            }
        });
        return ctx;
    }

    private HttpDefaultRequest createRequest(ChannelContext ctx) {
        return new HttpDefaultRequest(
                HttpMethod.GET, "/".getBytes(), "/", Collections.<String, List<String>>emptyMap(),
                HttpVersion.HTTP_1_1, Collections.<String, Object>singletonMap("Host", "localhost"),
                new byte[0], 0, null, ctx);
    }

    private static int freePort() {
        try (java.net.ServerSocket ss = new java.net.ServerSocket(0)) {
            return ss.getLocalPort();
        } catch (Exception e) {
            return 0;
        }
    }

    // ==================== observer only: normal request ====================

    @Test
    public void testObserverInvokedOnNormalRequest() throws Exception {
        ChannelContext ctx = createCtx();
        HttpDefaultRequest request = createRequest(ctx);
        SimpleCounterServerObserver metrics = new SimpleCounterServerObserver();
        HttpServerChannelHandler handler = new HttpServerChannelHandler();
        handler.setObserver(metrics);
        handler.setRequestHandler((req, resp) -> resp.status(200));
        handler.prepare();
        handler.onHandle(ctx, request);

        Map<String, Object> m = metrics.getMetrics();
        assertEquals(1L, m.get("totalRequests"));
        assertEquals(0L, m.get("activeRequests"));
        assertEquals(1L, m.get("success2xx"));
    }

    // ==================== observer only: application error ====================

    @Test
    public void testObserverInvokedOnApplicationError() throws Exception {
        ChannelContext ctx = createCtx();
        HttpDefaultRequest request = createRequest(ctx);
        SimpleCounterServerObserver metrics = new SimpleCounterServerObserver();
        HttpServerChannelHandler handler = new HttpServerChannelHandler();
        handler.setObserver(metrics);
        handler.setRequestHandler((req, resp) -> {
            throw new RuntimeException("boom");
        });
        handler.prepare();
        handler.onHandle(ctx, request);

        Map<String, Object> m = metrics.getMetrics();
        assertEquals(1L, m.get("serverError5xx"));
        assertEquals(1L, m.get("errorRequests"));
    }

    // ==================== observer only: connection lifecycle ====================

    @Test
    public void testConnectionObserver() throws Exception {
        ChannelContext ctx = createCtx();
        SimpleCounterServerObserver metrics = new SimpleCounterServerObserver();
        HttpServerChannelHandler handler = new HttpServerChannelHandler();
        handler.setObserver(metrics);
        handler.prepare();
        handler.onConnected(ctx);
        assertEquals(1L, metrics.getMetrics().get("totalConnections"));
        assertEquals(1L, metrics.getMetrics().get("activeConnections"));
        handler.onClosed(ctx);
        assertEquals(0L, metrics.getMetrics().get("activeConnections"));
    }

    // ==================== interceptor only: beforeHandle CONTINUE proceeds ====================

    @Test
    public void testInterceptorContinueProceeds() throws Exception {
        ChannelContext ctx = createCtx();
        HttpDefaultRequest request = createRequest(ctx);
        HttpServerInterceptor interceptor = mock(HttpServerInterceptor.class);
        when(interceptor.beforeHandle(any(), any(), any())).thenReturn(true);
        HttpServerChannelHandler handler = new HttpServerChannelHandler();
        handler.setInterceptor(interceptor);
        boolean[] called = {false};
        handler.setRequestHandler((req, resp) -> {
            called[0] = true;
            resp.status(200);
        });
        handler.prepare();
        handler.onHandle(ctx, request);
        assertTrue(called[0]);
        verify(interceptor).beforeHandle(any(), any(), any());
    }

    // ==================== interceptor only: beforeHandle RESPOND short-circuits ====================

    @Test
    public void testInterceptorBeforeHandleRespondShortCircuits() throws Exception {
        ChannelContext ctx = createCtx();
        HttpDefaultRequest request = createRequest(ctx);
        HttpServerInterceptor interceptor = new HttpServerInterceptor() {
            @Override
            public boolean beforeHandle(HttpRequest req, HttpResponse resp, ChannelContext c) {
                resp.status(401);
                return false;
            }
        };
        HttpServerChannelHandler handler = new HttpServerChannelHandler();
        handler.setInterceptor(interceptor);
        boolean[] called = {false};
        handler.setRequestHandler((req, resp) -> {
            called[0] = true;
            resp.status(200);
        });
        handler.prepare();
        handler.onHandle(ctx, request);
        // business handler must NOT run when the interceptor responds itself
        assertFalse(called[0]);
    }

    // ==================== interceptor beforeHandle THROWS: treated as short-circuit (false), business handler skipped ====================

    @Test
    public void testInterceptorBeforeHandleThrowsShortCircuits() throws Exception {
        ChannelContext ctx = createCtx();
        HttpDefaultRequest request = createRequest(ctx);
        HttpServerInterceptor interceptor = mock(HttpServerInterceptor.class);
        when(interceptor.beforeHandle(any(), any(), any())).thenThrow(new RuntimeException("interceptor boom"));
        HttpServerObserver observer = mock(HttpServerObserver.class);
        HttpServerChannelHandler handler = new HttpServerChannelHandler();
        handler.setInterceptor(interceptor);
        handler.setObserver(observer);
        boolean[] called = {false};
        handler.setRequestHandler((req, resp) -> {
            called[0] = true;
            resp.status(200);
        });
        handler.prepare();
        handler.onHandle(ctx, request);
        // business handler must NOT run; the delegate swallows the interceptor exception and returns false
        assertFalse(called[0]);
        // observer lifecycle still recorded: onRequestStart fired before the interceptor, onRequestComplete fired by the outer finally
        verify(observer).onRequestStart(any());
        verify(observer).onRequestComplete(any(), any(), anyLong(), any());
        // the thrown exception must not escape onHandle (no crash, error stays null)
        verify(observer).onRequestComplete(any(), any(), anyLong(), isNull());
    }

    // ==================== both set: interceptor short-circuits but observer still records completion ====================

    @Test
    public void testBothInterceptorShortCircuitObserverStillRecords() throws Exception {
        ChannelContext ctx = createCtx();
        HttpDefaultRequest request = createRequest(ctx);
        HttpServerInterceptor interceptor = new HttpServerInterceptor() {
            @Override
            public boolean beforeHandle(HttpRequest req, HttpResponse resp, ChannelContext c) {
                resp.status(403);
                return false;
            }
        };
        HttpServerObserver observer = mock(HttpServerObserver.class);
        HttpServerChannelHandler handler = new HttpServerChannelHandler();
        handler.setInterceptor(interceptor);
        handler.setObserver(observer);
        handler.setRequestHandler((req, resp) -> resp.status(200));
        handler.prepare();
        handler.onHandle(ctx, request);
        verify(observer).onRequestStart(any());
        verify(observer).onRequestComplete(any(), any(), anyLong(), any());
    }

    // ==================== neither set: delegate is null, no callbacks fired ====================

    @Test
    public void testNoInterceptorOrObserverDisabledByDefault() throws Exception {
        ChannelContext ctx = createCtx();
        HttpDefaultRequest request = createRequest(ctx);
        HttpServerChannelHandler handler = new HttpServerChannelHandler();
        handler.setRequestHandler((req, resp) -> resp.status(200));
        handler.prepare();
        handler.onHandle(ctx, request);
        // no exception; the disabled (null delegate) branch is exercised
    }

    // ==================== setObserver(null) disables observation ====================

    @Test
    public void testSetNullObserverDisablesObservation() throws Exception {
        ChannelContext ctx = createCtx();
        HttpDefaultRequest request = createRequest(ctx);
        HttpServerObserver observer = mock(HttpServerObserver.class);
        HttpServerChannelHandler handler = new HttpServerChannelHandler();
        handler.setObserver(observer);
        handler.setObserver(null); // disable
        handler.setRequestHandler((req, resp) -> resp.status(200));
        handler.prepare();
        handler.onHandle(ctx, request);
        verify(observer, never()).onRequestStart(any());
        verify(observer, never()).onRequestComplete(any(), any(), anyLong(), any());
    }

    // ==================== prepare() snapshots SPIs: post-prepare setObserver does NOT take effect (no hot-swap) ====================

    @Test
    public void testSetObserverAfterPrepareDoesNotTakeEffect() throws Exception {
        ChannelContext ctx = createCtx();
        HttpDefaultRequest request = createRequest(ctx);
        HttpServerObserver first = mock(HttpServerObserver.class);
        HttpServerObserver second = mock(HttpServerObserver.class);
        HttpServerChannelHandler handler = new HttpServerChannelHandler();
        handler.setObserver(first);
        handler.prepare();
        // change observer AFTER prepare: the already-assembled delegate keeps the first instance
        handler.setObserver(second);
        handler.setRequestHandler((req, resp) -> resp.status(200));
        handler.onHandle(ctx, request);
        // only the observer captured into the delegate at prepare() time fires
        verify(first).onRequestStart(any());
        verify(second, never()).onRequestStart(any());
    }

    // ==================== not an HttpRequest: nothing fired ====================

    @Test
    public void testInterceptorSkippedForNonHttpRequest() throws Exception {
        ChannelContext ctx = createCtx();
        HttpMessage nonRequest = mock(HttpMessage.class);
        when(nonRequest.isHttpRequest()).thenReturn(false);
        when(nonRequest.isUpgrade()).thenReturn(false);
        HttpServerInterceptor interceptor = mock(HttpServerInterceptor.class);
        HttpServerObserver observer = mock(HttpServerObserver.class);
        HttpServerChannelHandler handler = new HttpServerChannelHandler();
        handler.setInterceptor(interceptor);
        handler.setObserver(observer);
        handler.prepare();
        handler.onHandle(ctx, nonRequest);
        verify(interceptor, never()).beforeHandle(any(), any(), any());
        verify(observer, never()).onRequestStart(any());
        verify(observer, never()).onRequestComplete(any(), any(), anyLong(), any());
    }

    // ==================== HTTPServer.observer entry point ====================

    @Test
    public void testHttpserverObserverChain() {
        HTTPServer server = HTTPServer.of(freePort())
                .observer(mock(HttpServerObserver.class))
                .requestHandler(mock(HttpRequestHandler.class))
                .startupBannerEnabled(false)
                .start();
        assertNotNull(server);
        server.shutdown();
    }

    // ==================== HTTPServer.interceptor entry point ====================

    @Test
    public void testHttpserverInterceptorChain() {
        HTTPServer server = HTTPServer.of(freePort())
                .interceptor(mock(HttpServerInterceptor.class))
                .requestHandler(mock(HttpRequestHandler.class))
                .startupBannerEnabled(false)
                .start();
        assertNotNull(server);
        server.shutdown();
    }

    // ==================== handler.clear() triggers delegate.clear() (opt-in ClearableHandler) ====================

    @Test
    public void testObserverClearedOnHandlerClear() throws Exception {
        ChannelContext ctx = createCtx();
        SimpleCounterServerObserver metrics = new SimpleCounterServerObserver();
        HttpServerChannelHandler handler = new HttpServerChannelHandler();
        handler.setObserver(metrics);
        handler.setRequestHandler((req, resp) -> resp.status(200));
        handler.prepare();
        handler.onConnected(ctx);
        assertEquals(1L, metrics.getMetrics().get("totalConnections"));
        // a ClearableHandler observer is cleared when the handler is cleared (e.g. on server stop)
        handler.clear();
        assertEquals(0L, metrics.getMetrics().get("totalConnections"));
    }
}

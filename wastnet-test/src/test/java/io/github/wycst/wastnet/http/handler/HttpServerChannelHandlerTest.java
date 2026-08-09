package io.github.wycst.wastnet.http.handler;

import io.github.wycst.wastnet.http.*;
import io.github.wycst.wastnet.http.upgrade.UpgradeHandler;
import io.github.wycst.wastnet.socket.handler.ChannelHandler;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

public class HttpServerChannelHandlerTest {

    @Test void testSetRequestHandler() { new HttpServerChannelHandler().setRequestHandler(mock(HttpRequestHandler.class)); }
    @Test void testSetRequestHandlerNullThrows() { Assertions.assertThrows(NullPointerException.class, () -> new HttpServerChannelHandler().setRequestHandler(null)); }
    @Test void testSetUpgradeHandler() { HttpServerChannelHandler h = new HttpServerChannelHandler(); h.setUpgradeHandler(mock(UpgradeHandler.class)); Assertions.assertNotNull(h.getUpgradeHandler()); }
    @Test void testSetUpgradeHandlerNullThrows() { Assertions.assertThrows(NullPointerException.class, () -> new HttpServerChannelHandler().setUpgradeHandler(null)); }
    @Test void testSetChildHandler() { new HttpServerChannelHandler().setChildHandler(mock(ChannelHandler.class)); }
    @Test void testSetChildHandlerToSelfDoesNothing() { HttpServerChannelHandler h = new HttpServerChannelHandler(); h.setChildHandler(h); }
    @Test void testSetPrintStackTraceError() { new HttpServerChannelHandler().setPrintStackTraceError(true); }
    @Test void testSetExceptionHandler() { new HttpServerChannelHandler().setExceptionHandler(mock(HttpExceptionHandler.class)); }
    @Test void testGetUpgradeHandler() { Assertions.assertNotNull(new HttpServerChannelHandler().getUpgradeHandler()); }

    @Test void testPrepare() {
        HttpServerChannelHandler h = new HttpServerChannelHandler();
        h.setRequestHandler(new HttpRouterHandler());
        h.prepare();
        Assertions.assertTrue(h.getUpgradeHandler() instanceof HttpRouterHandler);
    }
    @Test void testOnConnectedNoChild() throws Exception { new HttpServerChannelHandler().onConnected(mock(ChannelContext.class)); }
    @Test void testOnConnectedWithChild() throws Exception {
        HttpServerChannelHandler h = new HttpServerChannelHandler();
        h.setChildHandler(mock(ChannelHandler.class));
        h.onConnected(mock(ChannelContext.class));
    }
    @Test void testOnClosedWithChild() throws Exception {
        HttpServerChannelHandler h = new HttpServerChannelHandler();
        h.setUpgradeHandler(mock(UpgradeHandler.class));
        h.setChildHandler(mock(ChannelHandler.class));
        h.onClosed(mock(ChannelContext.class));
    }
    @Test void testOnHandleUpgradeMessage() throws Exception {
        HttpServerChannelHandler h = new HttpServerChannelHandler();
        h.setUpgradeHandler(mock(UpgradeHandler.class));
        HttpUpgradeMessage msg = mock(HttpUpgradeMessage.class);
        when(msg.isUpgrade()).thenReturn(true);
        h.onHandle(mock(ChannelContext.class), msg);
    }
    @Test void testOnHandleSwallowsThrowable() throws Exception {
        HttpMessage msg = mock(HttpMessage.class);
        when(msg.isHttpRequest()).thenThrow(new RuntimeException());
        new HttpServerChannelHandler().onHandle(mock(ChannelContext.class), msg);
    }
    @Test void testAppExceptionInternalError() throws Throwable {
        HttpRequest request = mock(HttpRequest.class);
        when(request.isHttpRequest()).thenReturn(true);
        when(request.isBad()).thenReturn(false);
        HttpResponse response = mock(HttpResponse.class);
        when(response.getStatus()).thenReturn(HttpStatus.OK);
        when(response.getContentLength()).thenReturn(0L);
        doAnswer(inv -> response).when(response).body(any(byte[].class));
        doAnswer(inv -> response).when(response).status(any(HttpStatus.class));
        doAnswer(inv -> response).when(response).contentType(anyString());
        when(request.getResponse()).thenReturn(response);
        HttpRequestHandler throwingHandler = mock(HttpRequestHandler.class);
        doThrow(new RuntimeException()).when(throwingHandler).handle(request, response);
        HttpServerChannelHandler h = new HttpServerChannelHandler();
        h.setRequestHandler(throwingHandler);
        h.onHandle(mock(ChannelContext.class), request);
    }
    @Test void testProtocolErrorRequest() throws Exception {
        ChannelContext ctx = mock(ChannelContext.class);
        new HttpServerChannelHandler().onHandle(ctx, HttpBadRequest.PROTOCOL_ERROR_REQUEST);
        verify(ctx).close();
    }
}

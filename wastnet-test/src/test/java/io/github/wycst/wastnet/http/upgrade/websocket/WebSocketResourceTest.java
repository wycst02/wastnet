package io.github.wycst.wastnet.http.upgrade.websocket;

import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.http.upgrade.UpgradeWebSocketHolder;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Serializable;

import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link WebSocketResource}.
 * <p>
 * Covers connection management, broadcast, push, disconnect, subprotocol parsing,
 * and constructor variants. Uses mock WebSocketConnections to avoid real WebSocket setup.
 */
public class WebSocketResourceTest {

    private WebSocketResource resource;
    private WebSocketConnection conn1;
    private WebSocketConnection conn2;
    private WebSocketConnection conn3;

    @BeforeEach
    void setUp() {
        resource = new WebSocketResource(true, 300, WebSocketResource.TimeoutStrategy.PING);

        conn1 = mock(WebSocketConnection.class);
        conn2 = mock(WebSocketConnection.class);
        conn3 = mock(WebSocketConnection.class);

        when(conn1.id()).thenReturn("id1");
        when(conn2.id()).thenReturn("id2");
        when(conn3.id()).thenReturn("id3");
        when(conn1.getGroupId()).thenReturn("group-a");
        when(conn2.getGroupId()).thenReturn("group-a");
        when(conn3.getGroupId()).thenReturn("group-b");
        when(conn1.getAccount()).thenReturn("user1");
        when(conn2.getAccount()).thenReturn("user2");
        when(conn3.getAccount()).thenReturn("user1");

        // Register connections via handleOnOpen (public final method)
        resource.handleOnOpen(conn1);
        resource.handleOnOpen(conn2);
        resource.handleOnOpen(conn3);
    }

    // ==================== Registration ====================

    @Test
    void testHandleOnOpenAddsToConnectionMap() {
        Assertions.assertTrue(resource.connectionMap.containsKey("id1"));
        Assertions.assertTrue(resource.connectionMap.containsKey("id2"));
        Assertions.assertTrue(resource.connectionMap.containsKey("id3"));
        Assertions.assertEquals(3, resource.connectionMap.size());
    }

    @Test
    void testHandleOnCloseRemovesFromConnectionMap() {
        resource.handleOnClose(conn1, 1000, "normal");
        Assertions.assertFalse(resource.connectionMap.containsKey("id1"));
        Assertions.assertEquals(2, resource.connectionMap.size());
    }

    // ==================== Disconnect ====================

    @Test
    void testDisconnectAll() {
        resource.disconnect();
        verify(conn1).disconnect();
        verify(conn2).disconnect();
        verify(conn3).disconnect();
        Assertions.assertTrue(resource.connectionMap.isEmpty());
    }

    @Test
    void testDisconnectByGroup() {
        resource.disconnect("group-a");
        verify(conn1).disconnect();
        verify(conn2).disconnect();
        verify(conn3, never()).disconnect();
        Assertions.assertFalse(resource.connectionMap.containsKey("id1"));
        Assertions.assertFalse(resource.connectionMap.containsKey("id2"));
        Assertions.assertTrue(resource.connectionMap.containsKey("id3"));
    }

    @Test
    void testDisconnectByNullGroupDisconnectsAll() {
        resource.disconnect((Serializable) null);
        verify(conn1).disconnect();
        verify(conn2).disconnect();
        verify(conn3).disconnect();
    }

    @Test
    void testDisconnectByAccount() {
        resource.disconnectByAccount("user1");
        verify(conn1).disconnect();
        verify(conn2, never()).disconnect();
        verify(conn3).disconnect();
        Assertions.assertFalse(resource.connectionMap.containsKey("id1"));
        Assertions.assertFalse(resource.connectionMap.containsKey("id3"));
        Assertions.assertTrue(resource.connectionMap.containsKey("id2"));
    }

    @Test
    void testDisconnectByNullAccountReturnsEarly() {
        resource.disconnectByAccount(null);
        verify(conn1, never()).disconnect();
        Assertions.assertEquals(3, resource.connectionMap.size());
    }

    // ==================== Broadcast ====================

    @Test
    void testBroadcastToAll() {
        // WebSocketFrame is final, use real instance
        WebSocketFrame frame = new WebSocketFrame(WebSocketFrame.FrameType.TEXT, "hello".getBytes(), true);
        resource.broadcastMessage(frame);
        verify(conn1).push(frame);
        verify(conn2).push(frame);
        verify(conn3).push(frame);
    }

    @Test
    void testBroadcastToGroup() {
        WebSocketFrame frame = new WebSocketFrame(WebSocketFrame.FrameType.TEXT, "group-msg".getBytes(), true);
        resource.broadcastMessage(frame, "group-a");
        verify(conn1).push(frame);
        verify(conn2).push(frame);
        verify(conn3, never()).push(frame);
    }

    @Test
    void testBroadcastToNullGroupBroadcastsAll() {
        WebSocketFrame frame = new WebSocketFrame(WebSocketFrame.FrameType.TEXT, "broadcast".getBytes(), true);
        resource.broadcastMessage(frame, null);
        verify(conn1).push(frame);
        verify(conn2).push(frame);
        verify(conn3).push(frame);
    }

    // ==================== Push ====================

    @Test
    void testPushToAccount() {
        WebSocketFrame frame = new WebSocketFrame(WebSocketFrame.FrameType.TEXT, "push".getBytes(), true);
        resource.pushMessage(frame, "user1");
        // Two connections have "user1" account, only first one found gets the push
        verify(conn1).push(frame);
    }

    @Test
    void testPushToUnknownAccountDoesNothing() {
        WebSocketFrame frame = new WebSocketFrame(WebSocketFrame.FrameType.TEXT, "data".getBytes(), true);
        resource.pushMessage(frame, "nonexistent");
        verify(conn1, never()).push(frame);
        verify(conn2, never()).push(frame);
        verify(conn3, never()).push(frame);
    }

    // ==================== getConnectionByAccount ====================

    @Test
    void testGetConnectionByAccountFound() {
        WebSocketConnection found = resource.getConnectionByAccount("user2");
        Assertions.assertSame(conn2, found);
    }

    @Test
    void testGetConnectionByAccountNullReturnsNull() {
        Assertions.assertNull(resource.getConnectionByAccount(null));
    }

    @Test
    void testGetConnectionByAccountNotFound() {
        Assertions.assertNull(resource.getConnectionByAccount("ghost"));
    }

    // ==================== getSubprotocols ====================

    @Test
    void testGetSubprotocolsNullReturnsNull() {
        Assertions.assertNull(resource.getSubprotocols(null));
    }

    @Test
    void testGetSubprotocolsSingle() {
        Assertions.assertEquals("chat", resource.getSubprotocols("chat"));
    }

    @Test
    void testGetSubprotocolsMultiple() {
        Assertions.assertEquals("chat", resource.getSubprotocols("chat, superchat"));
    }

    // ==================== handleOnClose with ChannelContext ====================

    @Test
    void testHandleOnCloseCtxWithBindingRemovesAndNotifies() {
        // Track onErrorClose via anonymous subclass because handleOnClose(ChannelContext) is final
        boolean[] notified = {false};
        WebSocketResource tracked = new WebSocketResource(true, 300, WebSocketResource.TimeoutStrategy.PING) {
            @Override
            public void onErrorClose(WebSocketConnection c) {
                notified[0] = true;
            }
        };
        WebSocketConnection c = mock(WebSocketConnection.class);
        when(c.id()).thenReturn("idX");
        tracked.handleOnOpen(c);
        ChannelContext ctx = mock(ChannelContext.class);
        when(ctx.binding()).thenReturn(new UpgradeWebSocketHolder(tracked, c));
        tracked.handleOnClose(ctx);
        // broadcast=true: connection removed from map AND onErrorClose fires
        Assertions.assertTrue(notified[0]);
        Assertions.assertFalse(tracked.connectionMap.containsKey("idX"));
    }

    @Test
    void testHandleOnCloseCtxWithoutBindingDoesNothing() {
        ChannelContext ctx = mock(ChannelContext.class);
        when(ctx.binding()).thenReturn(null);
        resource.handleOnClose(ctx); // null binding: no removal, no callback, no exception
    }

    @Test
    void testHandleOnCloseCtxWithoutBroadcastStillNotifies() {
        // Key behaviour after the leak fix: onErrorClose must fire even with broadcast disabled
        boolean[] notified = {false};
        WebSocketResource tracked = new WebSocketResource(false, 300, WebSocketResource.TimeoutStrategy.PING) {
            @Override
            public void onErrorClose(WebSocketConnection c) {
                notified[0] = true;
            }
        };
        WebSocketConnection c = mock(WebSocketConnection.class);
        when(c.id()).thenReturn("idY");
        ChannelContext ctx = mock(ChannelContext.class);
        when(ctx.binding()).thenReturn(new UpgradeWebSocketHolder(tracked, c));
        tracked.handleOnClose(ctx);
        Assertions.assertTrue(notified[0]);
        Assertions.assertTrue(tracked.connectionMap.isEmpty());
    }

    // ==================== beforeHandshake ====================

    @Test
    void testBeforeHandshakeDefaultAllowsUpgrade() {
        HttpRequest req = mock(HttpRequest.class);
        HttpResponse resp = mock(HttpResponse.class);
        Assertions.assertTrue(resource.beforeHandshake(req, resp));
    }

    // ==================== Constructor variants ====================

    @Test
    void testConstructorWithTimeoutStrategy() {
        WebSocketResource r = new WebSocketResource(100, WebSocketResource.TimeoutStrategy.PING);
        Assertions.assertEquals(100, r.getTimeout());
        Assertions.assertEquals(WebSocketResource.TimeoutStrategy.PING, r.getTimeoutStrategy());
        Assertions.assertTrue(r.enableBroadcast());
    }

    @Test
    void testConstructorBoolInt() {
        WebSocketResource r = new WebSocketResource(false, 60);
        Assertions.assertFalse(r.enableBroadcast());
        Assertions.assertEquals(60, r.getTimeout());
    }

    @Test
    void testConstructorBool() {
        WebSocketResource r = new WebSocketResource(false);
        Assertions.assertFalse(r.enableBroadcast());
    }

    @Test
    void testResourceWithoutBroadcastDoesNotRegisterConnections() {
        WebSocketResource noBroadcast = new WebSocketResource(false);
        WebSocketConnection c = mock(WebSocketConnection.class);
        when(c.id()).thenReturn("test-id");
        noBroadcast.handleOnOpen(c);
        Assertions.assertTrue(noBroadcast.connectionMap.isEmpty());
    }

    @Test
    void testHandleOnCloseWithoutBroadcast() {
        WebSocketResource noBroadcast = new WebSocketResource(false);
        WebSocketConnection c = mock(WebSocketConnection.class);
        when(c.id()).thenReturn("test-id");
        noBroadcast.handleOnOpen(c);
        noBroadcast.handleOnClose(c, 1001, "going away");
        // No exception = success
    }

    // ===== Empty callback body coverage =====

    @Test
    void testOnMessageEmptyBody() throws IOException {
        resource.onMessage(mock(WebSocketConnection.class), "test");
    }

    @Test
    void testOnErrorEmptyBody() {
        resource.onError(mock(WebSocketConnection.class), new RuntimeException("test"));
    }
}

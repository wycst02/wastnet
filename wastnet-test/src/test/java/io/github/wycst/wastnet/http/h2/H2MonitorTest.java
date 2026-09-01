package io.github.wycst.wastnet.http.h2;

import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link H2Monitor} enabled mode.
 * <p>
 * Must be run with: {@code -Dwastnet.h2.monitor=true}
 *
 * @author wangyc
 */
@org.junit.jupiter.api.condition.DisabledOnJre(org.junit.jupiter.api.condition.JRE.JAVA_8)
public class H2MonitorTest {

    @BeforeAll
    static void forceEnable() {
        System.setProperty("wastnet.h2.monitor", "true");
    }

    static ChannelContext mockCtx() {
        ChannelContext ctx = mock(ChannelContext.class);
        when(ctx.getWriteBufferSize()).thenReturn(65535);
        return ctx;
    }

    @Test
    void testGlobalNeverThrows() {
        assertNotNull(H2Monitor.global());
    }

    @Test
    void testGlobalWithReaders() {
        Http2ServerReader r1 = new Http2ServerReader();
        H2Monitor.register(1, r1);
        try {
            Map<String, Object> stats = H2Monitor.global();
            assertEquals(1, (int) stats.get("connectionCount"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> conns = (List<Map<String, Object>>) stats.get("connections");
            assertEquals(1, conns.size());
            assertEquals(0, (int) conns.get(0).get("streamCount"));
        } finally {
            H2Monitor.unregister(1);
        }
    }

    @Test
    void testUnregisterRemovesConnection() {
        Http2ServerReader r = new Http2ServerReader();
        H2Monitor.register(999, r);
        H2Monitor.unregister(999);
        Map<String, Object> stats = H2Monitor.global();
        assertEquals(0, (int) stats.get("connectionCount"));
    }

    @Test
    void testStreamDetails() {
        Http2ServerReader r = new Http2ServerReader();
        r.connectSendWindow = 30000;
        Http2ServerStream s = new Http2ServerStream(r, 5, mockCtx());
        r.streamMap.put(5, s);
        H2Monitor.register(2, r);
        try {
            Map<String, Object> stats = H2Monitor.global();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> conns = (List<Map<String, Object>>) stats.get("connections");
            Map<String, Object> c = conns.get(0);
            assertEquals(1, (int) c.get("streamCount"));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> details = (List<Map<String, Object>>) c.get("streamDetails");
            assertEquals(1, details.size());
            assertEquals(5, (int) details.get(0).get("streamId"));
            assertTrue((Long) details.get(0).get("sendWindow") > 0);
            assertTrue((Long) details.get(0).get("recvWindow") > 0);
            assertTrue((Long) details.get(0).get("ageMs") >= 0);
        } finally {
            H2Monitor.unregister(2);
        }
    }

    @Test
    void testMultipleConnections() {
        Http2ServerReader r1 = new Http2ServerReader();
        r1.connectSendWindow = 10000;
        Http2ServerReader r2 = new Http2ServerReader();
        r2.connectSendWindow = 20000;
        H2Monitor.register(1, r1);
        H2Monitor.register(2, r2);
        try {
            Map<String, Object> stats = H2Monitor.global();
            assertEquals(2, (int) stats.get("connectionCount"));
            assertEquals(0L, stats.get("activeStreams"));
        } finally {
            H2Monitor.unregister(1);
            H2Monitor.unregister(2);
        }
    }

    @Test
    void testReset() {
        // Monitoring must be enabled (-Dwastnet.h2.monitor=true, see class javadoc).
        H2Monitor.incrTotalOpenStreams();
        H2Monitor.incrSubmitRequests();
        H2Monitor.incrTotalResponses(System.currentTimeMillis() - 50);
        H2Monitor.reset();
        Map<String, Object> after = H2Monitor.global();
        assertEquals(0L, (Long) after.get("totalOpenStreams"));
        assertEquals(0L, (Long) after.get("submitRequests"));
        assertEquals(0L, (Long) after.get("totalResponses"));
        assertEquals(0L, (Long) after.get("maxResponseTimeMs"));
    }
}

package io.github.wycst.wastnet.http.h2;

import io.github.wycst.wastnet.http.HttpConf;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * for the HTTP/2 Rapid Reset (CVE-2023-44487) defense:
 * {@link Http2ServerReader#onInboundRstStream} per-connection client RST rate limit.
 *
 * <p>The counting logic runs on the I/O thread and is pure (no real I/O); a mocked
 * {@link ChannelContext} is sufficient. {@code fireAbuse} / {@code close} are verified
 * via Mockito, and the package-private per-connection counters are read directly.</p>
 */
public class Http2RapidResetDefenseTest {

    // ==================== below threshold ====================

    @Test
    public void onInboundRstStreamBelowThresholdKeepsConnectionOpen() throws Exception {
        ChannelContext ctx = mock(ChannelContext.class);
        Http2ServerReader reader = new Http2ServerReader();
        int max = HttpConf.HTTP2_CLIENT_RST_MAX_COUNT;
        for (int i = 0; i < max; ++i) {
            reader.onInboundRstStream(ctx);
        }
        verify(ctx, never()).fireAbuse(anyString());
        assertTrue(reader.valid, "connection must stay open while the RST rate is within the limit");
    }

    // ==================== over threshold ====================

    @Test
    public void onInboundRstStreamOverThresholdFiresAbuseAndCloses() throws Exception {
        ChannelContext ctx = mock(ChannelContext.class);
        Http2ServerReader reader = new Http2ServerReader();
        int max = HttpConf.HTTP2_CLIENT_RST_MAX_COUNT;
        for (int i = 0; i < max; ++i) {
            reader.onInboundRstStream(ctx);
        }
        // the (max + 1)-th client RST exceeds the fixed-window limit
        reader.onInboundRstStream(ctx);
        verify(ctx, times(1)).fireAbuse("RST_FLOOD");
        verify(ctx).close();
        assertFalse(reader.valid, "connection must be closed after the RST rate limit is exceeded");
    }

    // ==================== fixed-window reset ====================

    @Test
    public void onInboundRstStreamWindowExpiryResetsCounter() throws Exception {
        ChannelContext ctx = mock(ChannelContext.class);
        Http2ServerReader reader = new Http2ServerReader();
        reader.onInboundRstStream(ctx);
        assertEquals(1, reader.rstCountInWindow);
        // simulate window expiry by moving the window start far into the past
        reader.rstWindowStartMs = System.currentTimeMillis() - 10_000L;
        reader.onInboundRstStream(ctx);
        // counter must reset to 1, not accumulate to 2
        assertEquals(1, reader.rstCountInWindow);
        verify(ctx, never()).fireAbuse(anyString());
    }
}

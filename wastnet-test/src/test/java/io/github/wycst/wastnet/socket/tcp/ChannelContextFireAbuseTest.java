package io.github.wycst.wastnet.socket.tcp;

import org.junit.jupiter.api.Test;

import java.nio.channels.SocketChannel;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage for {@link ChannelContext#fireAbuse}: dispatch to an AbuseHandler-aware
 * {@link ConnectionFilter} and silent ignore otherwise.
 */
public class ChannelContextFireAbuseTest {

    /** ConnectionFilter that also implements AbuseHandler -> must receive the event. */
    static class AbusiveFilter implements ConnectionFilter, AbuseHandler {
        ChannelContext ctx;
        String type;

        @Override
        public boolean onAccept(SocketChannel channel) {
            return true;
        }

        @Override
        public void onAbuse(ChannelContext ctx, String abuseType) {
            this.ctx = ctx;
            this.type = abuseType;
        }
    }

    /** Plain ConnectionFilter that does NOT implement AbuseHandler -> event ignored. */
    static class PlainFilter implements ConnectionFilter {
        @Override
        public boolean onAccept(SocketChannel channel) {
            return true;
        }
    }

    private ChannelContext newCtx(NioConfig nioConfig) throws Exception {
        ChannelContext ctx = new ChannelContext(SocketChannel.open(), 1024);
        ctx.setNioConfig(nioConfig);
        return ctx;
    }

    @Test
    public void fireAbuseDispatchesToAbuseHandler() throws Exception {
        NioConfig nioConfig = new NioConfig();
        ChannelContext ctx = newCtx(nioConfig);
        AbusiveFilter filter = new AbusiveFilter();
        nioConfig.setConnectionFilter(filter);

        ctx.fireAbuse("RST_FLOOD");

        assertSame(ctx, filter.ctx, "the firing context must be passed to onAbuse");
        assertEquals("RST_FLOOD", filter.type, "abuse type must be forwarded as-is");
    }

    @Test
    public void fireAbuseIgnoredWhenFilterNotAbuseHandler() throws Exception {
        NioConfig nioConfig = new NioConfig();
        ChannelContext ctx = newCtx(nioConfig);
        nioConfig.setConnectionFilter(new PlainFilter());

        // must be a silent no-op, not throw
        ctx.fireAbuse("RST_FLOOD");
    }

    @Test
    public void fireAbuseNoOpWhenNoConnectionFilter() throws Exception {
        NioConfig nioConfig = new NioConfig();
        ChannelContext ctx = newCtx(nioConfig);
        // no filter set at all -> getConnectionFilter() returns null -> ignored

        ctx.fireAbuse("RST_FLOOD");
    }
}

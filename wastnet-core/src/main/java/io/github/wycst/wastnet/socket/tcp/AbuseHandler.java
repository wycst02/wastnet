package io.github.wycst.wastnet.socket.tcp;

/**
 * Optional extension to {@link ConnectionFilter} for receiving abuse-event notifications.
 * <p>
 * Implement this interface on your {@code ConnectionFilter} instance to be notified when
 * abusive client behaviour is detected (e.g. HTTP/2 Rapid Reset, reported as
 * {@code "RST_FLOOD"}). The framework fires the event via {@link ChannelContext#fireAbuse};
 * if the registered {@code ConnectionFilter} does not implement this interface, the event
 * is silently ignored.
 * <p>
 * Implementations MUST be non-blocking and cheap (e.g. offer the client IP to a concurrent
 * collection) since the callback runs on the I/O worker thread.
 *
 * @author wangyc
 */
public interface AbuseHandler {

    /**
     * Called when abusive client behaviour is detected, immediately before the offending
     * connection is terminated.
     *
     * @param ctx       the channel context (use {@code ctx.getRemoteAddress()} for the client IP)
     * @param abuseType a short identifier for the abuse, e.g. {@code "RST_FLOOD"}
     */
    void onAbuse(ChannelContext ctx, String abuseType);
}

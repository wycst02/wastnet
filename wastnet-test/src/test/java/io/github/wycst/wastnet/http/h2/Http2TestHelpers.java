package io.github.wycst.wastnet.http.h2;

import io.github.wycst.wastnet.socket.tcp.ChannelContext;

/**
 * Test helper (same package as Http2Stream) so tests in other packages can build a
 * fully-configured client response stream without reaching into package-private fields.
 */
public final class Http2TestHelpers {

    private Http2TestHelpers() {
    }

    public static Http2ClientStream responseStream(Http2ClientReader reader, int id, ChannelContext ctx,
                                                   int statusCode, int declaredContentLength, String contentType,
                                                   byte[] body) {
        Http2ClientStream s = new Http2ClientStream(reader, id, ctx);
        s.statusCode = statusCode;
        s.declaredContentLength = declaredContentLength;
        s.contentType = contentType;
        s.bodyData = body;
        s.headers.put(":status", String.valueOf(statusCode));
        if (contentType != null) {
            s.headers.put("content-type", contentType);
        }
        return s;
    }

    public static Http2ClientStream errorStream(Http2ClientReader reader, int id, ChannelContext ctx) {
        Http2ClientStream s = new Http2ClientStream(reader, id, ctx);
        s.serverProtocolError = true;
        return s;
    }
}

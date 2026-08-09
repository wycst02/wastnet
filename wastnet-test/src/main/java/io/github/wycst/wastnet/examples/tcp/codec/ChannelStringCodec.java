package io.github.wycst.wastnet.examples.tcp.codec;

import io.github.wycst.wastnet.socket.channel.LengthFrameCodec;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.io.IOException;
import java.nio.charset.Charset;

/**
 * String codec using varint-length framing: 1 byte if length &lt; 128, 4 bytes otherwise.
 * <p>
 * This is a demo subclass of {@link LengthFrameCodec} showing varint-length support.
 * Compatible with the original ChannelStringCodec wire format.
 *
 * @author wangyc
 */
public class ChannelStringCodec extends LengthFrameCodec<String> {

    final Charset charset;
    final int limitPackLength;

    public ChannelStringCodec() {
        this(Charset.defaultCharset(), -1);
    }

    public ChannelStringCodec(Charset charset, int limit) {
        super(4, 0, 4, limit <= 0 ? Integer.MAX_VALUE : limit);
        this.charset = charset;
        this.limitPackLength = limit;
    }

    @Override
    protected int readLength(byte[] buf, int offset) {
        byte first = buf[offset];
        if (first >= 0) return first;
        return ((first & 0x7F) << 24) | ((buf[offset + 1] & 0xFF) << 16)
                | ((buf[offset + 2] & 0xFF) << 8) | (buf[offset + 3] & 0xFF);
    }

    @Override
    protected int computeActualHeaderLength(int rawLength) {
        return rawLength < 128 ? 1 : 4;
    }

    @Override
    protected byte[] createHeader(int wireLength) {
        if (wireLength < 128) {
            return new byte[]{(byte) wireLength};
        }
        byte[] h = new byte[4];
        writeLength(h, 0, wireLength | 0x80000000);
        return h;
    }

    @Override
    protected byte[] encodeBody(ChannelContext ctx, String message) throws IOException {
        byte[] bytes = message.getBytes(charset);
        if (limitPackLength > 0 && bytes.length > limitPackLength) {
            throw new IOException("message length " + bytes.length + " exceeds limit " + limitPackLength);
        }
        return bytes;
    }

    @Override
    protected void decodeBody(ChannelContext ctx, byte[] body) throws IOException {
        ctx.invokeHandle(new String(body, charset));
    }
}

/*
 * Copyright 2026, wangyunchao.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.wycst.wastnet.socket.protocol;

import io.github.wycst.wastnet.socket.channel.LengthFrameCodec;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Codec for object-based message protocols over TCP.
 * <p>
 * Frame format (12-byte header + 2-byte CRC16 trailer):
 * <pre>
 * [Magic 4B][BodyLength 4B][SeqID 4B][Payload...][CRC16 2B]
 * </pre>
 * <ul>
 *   <li><b>Magic</b> — fixed bytes for frame validation</li>
 *   <li><b>BodyLength</b> — big-endian, body bytes only (excludes header and trailer)</li>
 *   <li><b>SeqID</b> — request/response correlation id</li>
 *   <li><b>CRC16</b> — CRC-16/ARC checksum over header + body bytes for frame boundary integrity</li>
 * </ul>
 * <p>
 * Uses a pluggable {@link ObjectProtocol} for object serialization.
 *
 * @param <T> the message type
 */
public class ObjectCodec<T> extends LengthFrameCodec<T> {

    private static final int DEFAULT_MAGIC = 0x57534E54; // "WSNT"
    private static final AtomicInteger seqGen = new AtomicInteger();
    private static final String ATTR_SEQ_ID = "_seq";

    private final ObjectProtocol protocol;
    private final int magic;
    private final boolean validate;
    private final short frameTrailer;

    /**
     * Create an ObjectCodec with default magic and validation disabled.
     */
    public ObjectCodec(int maxFrameLength, ObjectProtocol protocol) {
        this(maxFrameLength, protocol, DEFAULT_MAGIC, false);
    }

    /**
     * Create an ObjectCodec with custom magic and validation toggle.
     *
     * @param maxFrameLength maximum allowed total frame length
     * @param protocol       the object protocol adapter
     * @param magic          4-byte magic for frame validation
     * @param validate       true to validate magic and frameTrailer on receive, false to skip
     */
    public ObjectCodec(int maxFrameLength, ObjectProtocol protocol, int magic, boolean validate) {
        this(maxFrameLength, protocol, magic, validate, false);
    }

    /**
     * Create an ObjectCodec with full control.
     *
     * @param maxFrameLength       maximum allowed total frame length
     * @param protocol             the object protocol adapter
     * @param magic                4-byte magic for frame validation
     * @param validate             true to validate magic and frameTrailer on receive, false to skip
     * @param lengthIncludesHeader true if the length field value includes the header bytes
     */
    public ObjectCodec(int maxFrameLength, ObjectProtocol protocol, int magic,
                       boolean validate, boolean lengthIncludesHeader) {
        this(maxFrameLength, protocol, magic, validate, lengthIncludesHeader, (short) 0);
    }

    /**
     * Create an ObjectCodec with full control and frameTrailer seed.
     *
     * @param maxFrameLength       maximum allowed total frame length
     * @param protocol             the object protocol adapter
     * @param magic                4-byte magic for frame validation
     * @param validate             true to validate magic and frameTrailer on receive, false to skip
     * @param lengthIncludesHeader true if the length field value includes the header bytes
     * @param frameTrailer         frame trailer seed for boundary integrity check (default 0)
     */
    public ObjectCodec(int maxFrameLength, ObjectProtocol protocol, int magic,
                       boolean validate, boolean lengthIncludesHeader, short frameTrailer) {
        super(12, 4, 4, maxFrameLength, 2, lengthIncludesHeader);
        this.protocol = protocol;
        this.magic = magic;
        this.validate = validate;
        this.frameTrailer = frameTrailer;
    }

    // ==================== Write (encode) ====================

    @Override
    protected byte[] encodeBody(ChannelContext ctx, T message) throws Exception {
        return protocol.encode(message);
    }

    @Override
    protected byte[] createHeader(int wireLength) {
        byte[] header = new byte[12];
        writeInt(header, 0, magic);
        writeInt(header, 4, wireLength);
        writeInt(header, 8, seqGen.incrementAndGet());
        return header;
    }

    @Override
    protected void writeTrailer(byte[] buf, int offset) {
        writeShort(buf, offset, frameTrailer);
    }

    @Override
    protected final int readLength(byte[] buf, int offset) {
        return readInt(buf, offset);
    }

    // ==================== Read (decode) ====================

    @Override
    @SuppressWarnings("unchecked")
    protected void onFrame(ChannelContext ctx, byte[] header, byte[] body, byte[] trailer) throws IOException {
        if (validate) {
            int magicRead = readInt(header, 0);
            if (magicRead != magic) {
                onProtocolError(ctx, new IOException("magic mismatch: expected 0x" + Integer.toHexString(magic) + " but got 0x" + Integer.toHexString(magicRead)));
                return;
            }
            // Validate frame trailer for boundary integrity
            short trailerRead = readShort(trailer, 0);
            if (trailerRead != frameTrailer) {
                onProtocolError(ctx, new IOException("frameTrailer mismatch: expected 0x"
                        + Integer.toHexString(frameTrailer & 0xFFFF) + " but got 0x" + Integer.toHexString(trailerRead & 0xFFFF)));
                return;
            }
        }
        int seqId = readInt(header, 8);
        ctx.setAttribute(ATTR_SEQ_ID, seqId);
        T message;
        try {
            message = (T) protocol.decode(body);
        } catch (Exception e) {
            if (closeOnCodecError) {
                onProtocolError(ctx, new IOException("decode failed", e));
            }
            return;
        }
        ctx.invokeHandle(message);
    }


}

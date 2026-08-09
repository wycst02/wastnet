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
package io.github.wycst.wastnet.http.reader;

import io.github.wycst.wastnet.http.HttpMessage;
import io.github.wycst.wastnet.http.h2.H2Monitor;
import io.github.wycst.wastnet.http.h2.Http2MessageReader;
import io.github.wycst.wastnet.http.h2.Http2ServerReader;
import io.github.wycst.wastnet.http.upgrade.UpgradeHolder;
import io.github.wycst.wastnet.socket.channel.ChannelDecoder;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * <p> single instance in one thread to aggregator http request. </p>
 *
 * <p> one channel one reader</p>
 *
 * @since 2024-1-19
 * @author wangyc
 */
public final class HttpChannelProtocolReader extends ChannelDecoder<HttpMessage> {

    private HttpMessageReader<HttpMessage> reader;

    public HttpChannelProtocolReader() {
    }

    /**
     * Detect protocol and initialize the appropriate reader.
     * Priority: ALPN h2 → h2c (preface) → HTTP/1.x fallback.
     *
     * @param ctx the channel context for this connection
     */
    @Override
    public void init(ChannelContext ctx) throws Exception {
        if (ctx.isChannelClosed()) return;
        if ("h2".equals(ctx.getHandShakedApplicationProtocol())) {
            reader = new Http2ServerReader();
        } else if (!ctx.isSSL() && ctx.hasApplicationProtocol("h2c")) {
            byte[] buf = new byte[24];
            try {
                readInternal(ctx, buf, 0, 24, 10000L);
                if (Http2MessageReader.validatePreface(buf)) {
                    reader = new Http2ServerReader().replyServerSettings(ctx).registerGoawayOnShutdown(ctx);
                    H2Monitor.register(ctx.getId(), (Http2MessageReader) reader);
                    // skip init
                    return;
                }
                reader = new HttpRequestReader(ctx);
                reader.decode(ctx, buf, 0, 24);
            } catch (IOException e) {
                ctx.close();
                return;
            }
        } else {
            reader = new HttpRequestReader(ctx);
        }
        reader.init(ctx);
    }

    @Override
    public void decode(ChannelContext ctx, ByteBuffer buf) throws IOException {
        reader.decode(ctx, buf);
    }

    /** @param upgradeHolder protocol upgrade context (e.g. h2c upgrade from HTTP/1.1) */
    public void upgrade(UpgradeHolder upgradeHolder) {
        reader.upgrade(upgradeHolder);
    }

    /**
     * Switch the underlying protocol reader (e.g. from HTTP/1.x to HTTP/2).
     *
     * @param reader the new protocol reader
     */
    public void switchTo(HttpMessageReader<HttpMessage> reader) {
        this.reader = reader;
    }
}

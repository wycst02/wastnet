package io.github.wycst.wastnet.http.reader;

import io.github.wycst.wastnet.http.upgrade.UpgradeHolder;
import io.github.wycst.wastnet.socket.channel.ChannelBytesDecoder;

/**
 * @since 2024-1-27
 * @author wangyc
 */
public abstract class HttpMessageReader<T> extends ChannelBytesDecoder<T> {

    public void upgrade(UpgradeHolder upgradeHolder) {
    }
}

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
package io.github.wycst.wastnet.http.h2;

import java.util.Map;

/**
 * Callback notified when the HTTP/2 trailer block (RFC 7540 §8.1) is fully received.
 * <p>
 * <b>When is it fired?</b>
 * <ul>
 *   <li><b>Streaming mode</b>: invoked <i>synchronously on the connection's reader thread</i> at
 *       the exact moment the trailer header block is decoded. This is the same instant the request
 *       body stream is marked end-of-stream, so the application's {@code bodyStream.read()} will
 *       return {@code -1} immediately afterwards. Because it fires as soon as the trailer frame
 *       arrives, the callback may run <i>before</i> the application has finished consuming the
 *       body. If you must validate a trailer (e.g. {@code Digest}) against the whole body, do it
 *       <i>after</i> the application has read the entire body via
 *       {@link Http2Request#getTrailers()}, not inside this callback.</li>
 *   <li><b>Non-streaming mode</b>: the request is only submitted (dispatched) after the trailer
 *       block arrives, so there is no opportunity to register this listener in time. Read the
 *       trailers after dispatch via {@link Http2Request#getTrailers()} instead.</li>
 * </ul>
 */
public interface TrailersListener {

    /**
     * Invoked once the trailer header block has been fully decoded and the request body stream
     * reaches its end (the application's {@code read()} returns {@code -1} right after).
     * <p>
     * Fired synchronously on the reader thread. Do not assume the application has finished
     * consuming the body here; for body-dependent validation, read {@code req.getTrailers()}
     * after the body is fully read.
     *
     * @param trailers the decoded trailer fields (never {@code null})
     */
    void onTrailers(Map<String, Object> trailers);
}

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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight, decoupled H2 connection monitor. All monitoring logic lives here;
 * the framework only calls {@link #register} and {@link #unregister}.
 * <p>
 * Enabled via {@code -Dwastnet.h2.monitor=true}. When disabled, all methods
 * are no-ops and the JIT compiler eliminates the calls entirely.
 * <p>
 * Usage: call {@link #global()} to snapshot the current state of all live
 * H2 connections (flow-control windows, active streams, and diagnostic state
 * for locating stuck or leaked connections).
 *
 * @author wangyc
 */
public final class H2Monitor {

    private H2Monitor() {}

    /** Whether monitoring is enabled (read once at class init, folded by JIT). */
    private static final boolean ENABLED = Boolean.getBoolean("wastnet.h2.monitor");

    /** All live H2 connections, keyed by connection ID. */
    private static final ConcurrentHashMap<Long, Http2MessageReader> LIVE = new ConcurrentHashMap<>();

    /** Process-level cumulative counters, kept across connection lifecycles. */
    private static final AtomicLong TOTAL_OPEN_STREAMS = new AtomicLong();
    private static final AtomicLong SUBMIT_REQUESTS = new AtomicLong();
    private static final AtomicLong TOTAL_RESPONSES = new AtomicLong();
    /** Process-level max response time (ms), measured from stream creation to completion. */
    private static final AtomicLong MAX_RESPONSE_TIME_MS = new AtomicLong();

    /** Increment the process-level stream-open counter. */
    public static void incrTotalOpenStreams() {
        if (ENABLED) TOTAL_OPEN_STREAMS.incrementAndGet();
    }

    /** Increment the process-level request-submit counter. */
    public static void incrSubmitRequests() {
        if (ENABLED) SUBMIT_REQUESTS.incrementAndGet();
    }

    /**
     * Count one completed response and track the max response time.
     *
     * @param createdAt stream creation timestamp (ms), used to compute elapsed time
     */
    public static void incrTotalResponses(long createdAt) {
        if (!ENABLED) return;
        TOTAL_RESPONSES.incrementAndGet();
        long elapsed = System.currentTimeMillis() - createdAt;
        while (true) {
            long cur = MAX_RESPONSE_TIME_MS.get();
            if (elapsed <= cur || MAX_RESPONSE_TIME_MS.compareAndSet(cur, elapsed)) {
                break;
            }
        }
    }

    /**
     * Reset all process-level cumulative counters and the max response time.
     * Provided for application-level use (e.g. before a benchmark window).
     */
    public static void reset() {
        if (!ENABLED) return;
        TOTAL_OPEN_STREAMS.set(0);
        SUBMIT_REQUESTS.set(0);
        TOTAL_RESPONSES.set(0);
        MAX_RESPONSE_TIME_MS.set(0);
    }

    /** Register a live H2 connection. Called after successful handshake. */
    public static void register(long connId, Http2MessageReader r) {
        if (ENABLED) LIVE.put(connId, r);
    }

    /** Remove a connection. Called on close / GOAWAY / error. */
    public static void unregister(long connId) {
        if (ENABLED) LIVE.remove(connId);
    }

    /**
     * Return a structured snapshot of all live connections.
     */
    public static Map<String, Object> global() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (!ENABLED) { m.put("enabled", false); return m; }
        List<Map<String, Object>> conns = new ArrayList<>(LIVE.size());
        long activeStreams = 0;
        for (Map.Entry<Long, Http2MessageReader> e : LIVE.entrySet()) {
            Http2MessageReader r = e.getValue();
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("connectionId", e.getKey());
            cm.put("streamCount", r.streamMap.size());
            cm.put("maxStreamId", r.currentMaxStreamId);
            cm.put("sendWindow", r.connectSendWindow);
            cm.put("recvWindow", r.connectRecvWindow.get());
            cm.put("recvWindowInit", r.initConnectReceiveWindowSize);
            // diagnostic state (monitoring only)
            r.fillDiagnostic(cm);

            List<Map<String, Object>> streams = new ArrayList<>(r.streamMap.size());
            for (Http2Stream s : r.streamMap.values()) {
                Map<String, Object> sm = new LinkedHashMap<>();
                sm.put("streamId", s.streamId);
                sm.put("sendWindowInit", r.streamInitSendWindowSize);
                sm.put("recvWindowInit", r.initialReceiveWindowSize);
                // diagnostic state (monitoring only)
                s.fillDiagnostic(sm);
                streams.add(sm);
            }
            cm.put("streamDetails", streams);
            conns.add(cm);
            activeStreams += r.streamMap.size();
        }
        m.put("enabled", true);
        m.put("connectionCount", conns.size());
        m.put("connections", conns);
        m.put("activeStreams", activeStreams);
        m.put("totalOpenStreams", TOTAL_OPEN_STREAMS.get());
        m.put("submitRequests", SUBMIT_REQUESTS.get());
        m.put("totalResponses", TOTAL_RESPONSES.get());
        m.put("maxResponseTimeMs", MAX_RESPONSE_TIME_MS.get());
        return m;
    }
}

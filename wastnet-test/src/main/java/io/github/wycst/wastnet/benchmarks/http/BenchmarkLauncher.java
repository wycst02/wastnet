package io.github.wycst.wastnet.benchmarks.http;

/**
 * Single-entry launcher for benchmark servers.
 * <p>
 * Run with system properties, e.g.:
 *   -Dimpl=wastnet -Dproto=h1  -Dport=8080
 *   -Dimpl=wastnet -Dproto=h1p -Dport=8080 -Dwastnet.http.pipeline.enabled=true
 *   -Dimpl=wastnet -Dproto=h2  -Dport=8443   (h2 over TLS)
 *   -Dimpl=wastnet -Dproto=h2c -Dport=8445   (cleartext HTTP/2)
 *   -Dimpl=undertow -Dproto=h1  -Dport=8081
 *   -Dimpl=undertow -Dproto=h1p -Dport=8081
 *   -Dimpl=undertow -Dproto=h2  -Dport=8444
 *   -Dimpl=undertow -Dproto=h2c -Dport=8446
 * <p>
 * impl:  `wastnet` | `undertow`
 * proto: `h1` | `h1p` (http1-pipeline) | `h2` | `h2c`
 *   `h1`  - plain HTTP/1.1, pipelining disabled
 *   `h1p` - plain HTTP/1.1 with pipelining enabled (for h2load --h1, MUST pass
 *          -Dwastnet.http.pipeline.enabled=true before HttpConf class loads)
 *   `h2`  - HTTP/2 over TLS
 *   `h2c` - cleartext HTTP/2 (prior-knowledge preface)
 */
public class BenchmarkLauncher {

    public static void main(String[] args) throws Exception {
        String impl = System.getProperty("impl", "wastnet").toLowerCase();
        String proto = System.getProperty("proto", "h1").toLowerCase();
        int port = Integer.getInteger("port", 8080);

        boolean http2 = "h2".equals(proto) || "h2c".equals(proto);
        boolean h2Tls = "h2".equals(proto);

        BenchmarkServer server;
        if ("undertow".equals(impl)) {
            server = new UndertowServer(http2, h2Tls);
        } else {
            server = new WastnetServer(http2, h2Tls);
        }

        String label = impl + " " + proto + " on port " + port;
        server.start(port);
        System.out.println("[benchmark] " + label + " started. Press Ctrl+C to stop.");
        Thread.currentThread().join();
    }
}

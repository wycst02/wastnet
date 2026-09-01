package io.github.wycst.wastnet.http;

import io.github.wycst.wastnet.http.handler.HttpExceptionHandler;
import io.github.wycst.wastnet.http.handler.HttpRequestHandler;
import io.github.wycst.wastnet.http.proxy.H2H1ProxyAdapter;
import io.github.wycst.wastnet.http.proxy.HttpProxyConnection;
import io.github.wycst.wastnet.http.proxy.HttpProxyWorker;
import io.github.wycst.wastnet.http.reader.HttpChannelProtocolReader;
import io.github.wycst.wastnet.http.reader.HttpMessageReader;
import io.github.wycst.wastnet.http.upgrade.UpgradeHandler;
import io.github.wycst.wastnet.http.upgrade.UpgradeHolder;
import io.github.wycst.wastnet.socket.channel.ChannelReader;
import io.github.wycst.wastnet.socket.handler.ChannelHandler;
import io.github.wycst.wastnet.socket.handler.IdleStateHandler;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import io.github.wycst.wastnet.socket.tcp.ConnectionFilter;
import io.github.wycst.wastnet.socket.tcp.NioConfig;
import io.github.wycst.wastnet.socket.tcp.PEMSSLContextFactory;
import io.github.wycst.wastnet.socket.tcp.SSLContextFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive coverage for small classes with low branch coverage.
 */
public class SmallClassCoverageTest {

    // ==================== BadHttpRequest ====================

    @Test
    public void testBadHttpRequestDefault() {
        HttpBadRequest req = new HttpBadRequest();
        assertTrue(req.isBad());
        assertEquals(HttpStatus.BAD_REQUEST, req.getHttpStatus());
        assertEquals(HttpStatus.BAD_REQUEST.text, req.getErrorMessage());
        assertEquals(HttpStatus.BAD_REQUEST.code, req.getHttpStatusCode());
        assertTrue(req.isCompleted());
    }

    @Test
    public void testBadHttpRequestStatusChain() {
        HttpBadRequest req = new HttpBadRequest();
        req.status(HttpStatus.NOT_FOUND).stream(false).chunked(false);
        assertEquals(HttpStatus.NOT_FOUND, req.getHttpStatus());
        assertTrue(req.isCompleted());
    }

    @Test
    public void testBadHttpRequestStreamChunked() throws Exception {
        SocketChannel ch = SocketChannel.open();
        ch.configureBlocking(false);
        ChannelContext ctx = new ChannelContext(ch, 4096);

        byte[] body = "test".getBytes();
        Map<String, Object> headers = new HashMap<String, Object>();
        Map<String, List<String>> params = new HashMap<String, List<String>>();

        HttpBadRequest req = new HttpBadRequest(
                HttpMethod.POST, "/test".getBytes(), "/test", params,
                HttpVersion.HTTP_1_1, headers, body, 4, "text/plain", ctx);
        req.stream(true).chunked(true);
        assertFalse(req.isCompleted());
        // complete() should not throw despite stream=true
        req.complete();

        ch.close();
    }

    @Test
    public void testBadHttpRequestStreamNormal() throws Exception {
        SocketChannel ch = SocketChannel.open();
        ch.configureBlocking(false);
        ChannelContext ctx = new ChannelContext(ch, 4096);

        HttpBadRequest req = new HttpBadRequest();
        req.stream(true).chunked(false);
        req.complete();

        ch.close();
    }

    @Test
    public void testBadHttpRequestToString() {
        HttpBadRequest req = new HttpBadRequest();
        String s = req.toString();
        assertTrue(s.contains("BadHttpRequest"));
        assertTrue(s.contains("BAD_REQUEST"));
    }

    // ==================== MultipartFieldFile ====================

    @Test
    public void testMultipartFieldFile(@TempDir File tmpDir) throws Exception {
        // Create a temp file with content
        File tempFile = new File(tmpDir, "upload.tmp");
        java.nio.file.Files.write(tempFile.toPath(), "file content".getBytes());

        HttpBuf nameBuf = HttpBuf.wrap("field".getBytes(), 0, 5);
        HttpBuf fileNameBuf = HttpBuf.wrap("test.txt".getBytes(), 0, 8);
        HttpBuf contentTypeBuf = HttpBuf.wrap("text/plain".getBytes(), 0, 10);

        MultipartFieldFile field = new MultipartFieldFile(
                "field", fileNameBuf, contentTypeBuf, tempFile, StandardCharsets.UTF_8);
        assertEquals("field", field.getName());

        // getInputStream
        InputStream is = field.getInputStream();
        assertNotNull(is);
        is.close();

        // transferTo
        File dest = new File(tmpDir, "dest.txt");
        field.transferTo(dest, false);
        assertTrue(dest.exists());

        // append mode
        field.transferTo(dest, true);

        // getData throws
        assertThrows(UnsupportedOperationException.class, field::getData);

        // isTempFile
        assertTrue(field.isTempFile());

        // release
        field.release();
        assertFalse(tempFile.exists());
    }

    @Test
    public void testMultipartFieldFileReleaseNonExistent(@TempDir File tmpDir) {
        File tempFile = new File(tmpDir, "nonexistent.tmp");
        HttpBuf nameBuf = HttpBuf.wrap("x".getBytes(), 0, 1);
        MultipartFieldFile field = new MultipartFieldFile(
                "x", null, null, tempFile, StandardCharsets.UTF_8);
        // release on non-existent file should be safe
        field.release();
    }

    // ==================== H2H1ProxyAdapter ====================

    @Test
    public void testH2H1ProxyAdapterTryAcquire() throws Exception {
        HttpProxyWorker worker = new HttpProxyWorker();
        SocketChannel ch = SocketChannel.open();
        ch.configureBlocking(false);
        ChannelContext ctx = new ChannelContext(ch, 4096);
        HttpProxyConnection conn = new HttpProxyConnection(1L, "route", ctx, ctx, worker);
        H2H1ProxyAdapter adapter = new H2H1ProxyAdapter(conn);
        // busy starts true; use reflection to reset for test
        java.lang.reflect.Field busyField = H2H1ProxyAdapter.class.getDeclaredField("busy");
        busyField.setAccessible(true);
        ((java.util.concurrent.atomic.AtomicBoolean) busyField.get(adapter)).set(false);
        assertTrue(adapter.tryAcquire());
        ch.close();
    }

    @Test
    public void testH2H1ProxyAdapterOnDataNotTarget() throws Exception {
        HttpProxyWorker worker = new HttpProxyWorker();
        SocketChannel ch = SocketChannel.open();
        ch.configureBlocking(false);
        ChannelContext ctx = new ChannelContext(ch, 4096);
        HttpProxyConnection conn = new HttpProxyConnection(1L, "route", ctx, ctx, worker);
        H2H1ProxyAdapter adapter = new H2H1ProxyAdapter(conn);
        // isTarget=false should return immediately
        adapter.onData(ByteBuffer.allocate(10), ctx, false, conn);
        ch.close();
    }

    // ==================== HttpChannelProtocolReader ====================

    @Test
    public void testHttpChannelProtocolReaderInitClosedChannel() throws Exception {
        ChannelContext ctx = mock(ChannelContext.class);
        when(ctx.isChannelClosed()).thenReturn(true);
        HttpChannelProtocolReader reader = new HttpChannelProtocolReader();
        // Should return immediately without exception
        reader.init(ctx);
        verify(ctx).isChannelClosed();
    }

    @Test
    public void testHttpChannelProtocolReaderInitH2() throws Exception {
        ChannelContext ctx = mock(ChannelContext.class);
        when(ctx.isChannelClosed()).thenReturn(false);
        when(ctx.getHandShakedApplicationProtocol()).thenReturn("h2");
        HttpChannelProtocolReader reader = new HttpChannelProtocolReader();
        // Should set up Http2ServerReader without throwing
        reader.init(ctx);
        verify(ctx).getHandShakedApplicationProtocol();
    }

    @Test
    public void testHttpChannelProtocolReaderInitElse() throws Exception {
        ChannelContext ctx = mock(ChannelContext.class);
        when(ctx.isChannelClosed()).thenReturn(false);
        when(ctx.getHandShakedApplicationProtocol()).thenReturn(null);
        HttpChannelProtocolReader reader = new HttpChannelProtocolReader();
        reader.init(ctx);
        verify(ctx).getHandShakedApplicationProtocol();
    }

    @Test
    public void testHttpChannelProtocolReaderSwitchToAndDecode() throws Exception {
        HttpMessageReader<HttpMessage> mockReader = mock(HttpMessageReader.class);
        HttpChannelProtocolReader reader = new HttpChannelProtocolReader();
        reader.switchTo(mockReader);

        ChannelContext ctx = mock(ChannelContext.class);
        ByteBuffer buf = ByteBuffer.wrap("hello".getBytes());
        reader.decode(ctx, buf);

        // Verify decode delegates to the switched reader
        verify(mockReader).decode(same(ctx), any(byte[].class), eq(0), eq(5));
    }

    @Test
    public void testHttpChannelProtocolReaderUpgrade() {
        HttpMessageReader<HttpMessage> mockReader = mock(HttpMessageReader.class);
        HttpChannelProtocolReader reader = new HttpChannelProtocolReader();
        reader.switchTo(mockReader);

        UpgradeHolder holder = mock(UpgradeHolder.class);
        reader.upgrade(holder);

        verify(mockReader).upgrade(same(holder));
    }

    // ==================== HttpChannelProtocolReader h2c paths (real TCP) ====================

    /** Build a connected server-client socket pair */
    private static SocketChannel[] createConnectedPair() throws Exception {
        ServerSocketChannel ssc = ServerSocketChannel.open();
        ssc.bind(new InetSocketAddress("127.0.0.1", 0));
        SocketChannel client = SocketChannel.open(ssc.getLocalAddress());
        SocketChannel server = ssc.accept();
        server.configureBlocking(false);
        client.configureBlocking(false);
        ssc.close();
        return new SocketChannel[]{server, client};
    }

    private static int freePort() {
        try (java.net.ServerSocket ss = new java.net.ServerSocket(0)) {
            return ss.getLocalPort();
        } catch (Exception e) {
            return 0;
        }
    }

    /** Set up a ChannelContext with h2c protocol configured */
    private static ChannelContext createH2cContext(SocketChannel serverChannel) throws Exception {
        ChannelContext ctx = new ChannelContext(serverChannel, 4096);
        NioConfig nioConfig = new NioConfig();
        nioConfig.setApplicationProtocols(new String[]{"h2c"});
        Field nf = ChannelContext.class.getDeclaredField("nioConfig");
        nf.setAccessible(true);
        nf.set(ctx, nioConfig);
        return ctx;
    }

    @Test
    public void testHttpChannelProtocolReaderInitH2cValidPreface() throws Exception {
        SocketChannel[] pair = createConnectedPair();
        SocketChannel serverChannel = pair[0];
        SocketChannel clientChannel = pair[1];

        try {
            // Write valid HTTP/2 client connection preface (PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n)
            byte[] preface = new byte[]{
                    0x50, 0x52, 0x49, 0x20, 0x2A, 0x20, 0x48, 0x54,
                    0x54, 0x50, 0x2F, 0x32, 0x2E, 0x30, 0x0D, 0x0A,
                    0x0D, 0x0A, 0x53, 0x4D, 0x0D, 0x0A, 0x0D, 0x0A
            };
            ByteBuffer prefaceBuf = ByteBuffer.wrap(preface);
            while (prefaceBuf.hasRemaining()) {
                clientChannel.write(prefaceBuf);
            }

            ChannelContext ctx = createH2cContext(serverChannel);
            HttpChannelProtocolReader reader = new HttpChannelProtocolReader();
            reader.init(ctx);
            // Should reach h2c path, validate preface as valid, create Http2ServerReader
        } finally {
            clientChannel.close();
            serverChannel.close();
        }
    }

    @Test
    public void testHttpChannelProtocolReaderInitH2cInvalidPreface() throws Exception {
        SocketChannel[] pair = createConnectedPair();
        SocketChannel serverChannel = pair[0];
        SocketChannel clientChannel = pair[1];

        try {
            // Write enough non-preface data (24+ bytes) to satisfy readFully
            // "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n" = 36 bytes > 24
            byte[] nonPreface = "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes();
            ByteBuffer dataBuf = ByteBuffer.wrap(nonPreface);
            while (dataBuf.hasRemaining()) {
                clientChannel.write(dataBuf);
            }

            ChannelContext ctx = createH2cContext(serverChannel);
            HttpChannelProtocolReader reader = new HttpChannelProtocolReader();
            reader.init(ctx);
            // Should reach h2c path, validate preface as invalid,
            // create HttpRequestReader and decode the 24 bytes
        } finally {
            clientChannel.close();
            serverChannel.close();
        }
    }

    @Test
    public void testHttpChannelProtocolReaderInitH2cIOException() throws Exception {
        SocketChannel[] pair = createConnectedPair();
        SocketChannel serverChannel = pair[0];
        SocketChannel clientChannel = pair[1];
        clientChannel.close(); // close client so server read returns -1

        try {
            ChannelContext ctx = createH2cContext(serverChannel);
            HttpChannelProtocolReader reader = new HttpChannelProtocolReader();
            reader.init(ctx);
            // readInternal should throw IOException, caught by catch block,
            // ctx.close() should be called, method returns early
        } finally {
            serverChannel.close();
        }
    }

    // ==================== HTTPServer ====================

    @Test
    public void testHttpserverFactoryWithConfig() {
        NioConfig nc = new NioConfig();
        HTTPServer s = HTTPServer.of(freePort(), nc);
        assertNotNull(s);
        s.shutdown();
    }

    @Test
    public void testHttpserverBuilderChains() {
        NioConfig nc = new NioConfig();
        HTTPServer s = HTTPServer.of(freePort(), nc)
                .ssl(true)
                .sslContext(mock(SSLContext.class))
                .sslContextFactory(mock(SSLContextFactory.class))
                .bufferSize(1024)
                .workerNum(1)
                .printSSLErrorLog(true)
                .printReadErrorLog(false)
                .printApplicationMessage(true)
                .printStackTraceError(false)
                .localOnly(true)
                .exceptionHandler(mock(HttpExceptionHandler.class))
                .channelReader(mock(ChannelReader.class))
                .channelHandler(mock(ChannelHandler.class))
                .idleStateHandler(mock(IdleStateHandler.class))
                .connectionFilter(mock(ConnectionFilter.class))
                .upgradeHandler(mock(UpgradeHandler.class))
                .startupBannerEnabled(false)
                .sslCipherSuites("TLS_AES_128_GCM_SHA256");
        assertNotNull(s);
        s.shutdown();
    }

    @Test
    public void testHttpserverUpgradeHandler() {
        HTTPServer s = HTTPServer.of(freePort()).localOnly(true)
                .requestHandler(mock(HttpRequestHandler.class))
                .startupBannerEnabled(false)
                .start();
        assertNotNull(s.upgradeHandler());
        s.shutdown();
    }

    @Test
    public void testHttpserverH2AndAlpn() {
        HTTPServer s = HTTPServer.of(freePort())
                .h2()
                .applicationProtocols("h2", "http/1.1")
                .startupBannerEnabled(false)
                .start();
        s.shutdown();
    }

    @Test
    public void testHttpserverNonLocal() {
        HTTPServer s = HTTPServer.of(freePort())
                .requestHandler(mock(HttpRequestHandler.class))
                .startupBannerEnabled(false)
                .start();
        s.shutdown();
    }

    @Test
    public void testHttpserverPemSsl() {
        HTTPServer s = HTTPServer.of(freePort())
                .pemSSL("classpath:cert/cert.pem", "classpath:cert/server.pem")
                .h2()
                .startupBannerEnabled(false)
                .start();
        s.shutdown();
    }

    // ==================== HttpMethod ====================

    @Test
    public void testHttpMethodFromString() {
        assertSame(HttpMethod.GET, HttpMethod.fromString("GET"));
        assertSame(HttpMethod.POST, HttpMethod.fromString("POST"));
        assertNull(HttpMethod.fromString(null));
        assertNull(HttpMethod.fromString("INVALID"));
    }

    // ==================== HttpBodyDefaultDecoder ====================

    @Test
    public void testHttpBodyDefaultDecoderBasic() {
        byte[] query = "key1=value1&key2=value2".getBytes();
        // form-urlencoded content type
        HttpBodyDefaultDecoder decoder = new HttpBodyDefaultDecoder("application/x-www-form-urlencoded", query);
        decoder.release();
        // multipart content type
        byte[] empty = new byte[0];
        HttpBodyDefaultDecoder decoder2 = new HttpBodyDefaultDecoder("multipart/form-data; boundary=--boundary", empty);
        decoder2.release();
    }

    // ==================== PEMSSLContextFactory ====================

    @Test
    public void testPemSslFactoryCreateInvalidPath() {
        assertThrows(RuntimeException.class, () -> new PEMSSLContextFactory("nonexistent/cert.pem", "nonexistent/key.pem"));
    }

    // ==================== HttpRequest attribute / parameter methods ====================

    @Test
    public void testRequestAttributes() throws Exception {
        ChannelContext ctx = new ChannelContext(SocketChannel.open(), 4096);
        Map<String, Object> headers = new HashMap<String, Object>();
        HttpDecodedRequest request = new HttpDecodedRequest(HttpVersion.HTTP_1_1, headers, ctx) {
        };
        request.setAttribute("key1", "val1");
        assertEquals("val1", request.getAttribute("key1"));
        assertNull(request.getAttribute("nonexistent"));

        request.removeAttribute("key1");
        assertNull(request.getAttribute("key1"));

        request.setAttribute("a", "1");
        request.setAttribute("b", "2");
        Enumeration<String> names = request.getAttributeNames();
        Set<String> nameSet = new HashSet<String>();
        while (names.hasMoreElements()) nameSet.add(names.nextElement());
        assertTrue(nameSet.contains("a"));
        assertTrue(nameSet.contains("b"));
        ctx.close();
    }

    @Test
    public void testRequestGetParameterMapEmpty() throws Exception {
        ChannelContext ctx = new ChannelContext(SocketChannel.open(), 4096);
        Map<String, Object> headers = new HashMap<String, Object>();
        HttpDecodedRequest request = new HttpDecodedRequest(HttpVersion.HTTP_1_1, headers, ctx) {
        };
        Map<String, String[]> map = request.getParameterMap();
        assertNotNull(map);
        assertTrue(map.isEmpty());
        ctx.close();
    }

    @Test
    public void testRequestGetParameterMapNonEmpty() throws Exception {
        ChannelContext ctx = new ChannelContext(SocketChannel.open(), 4096);
        Map<String, Object> headers = new HashMap<String, Object>();
        Map<String, List<String>> params = new HashMap<String, List<String>>();
        params.put("key", Collections.singletonList("val"));
        HttpDecodedRequest request = new HttpDecodedRequest(
                HttpMethod.GET, "/test?key=val".getBytes(), "/test", params,
                HttpVersion.HTTP_1_1, headers, new byte[0], 0, null, ctx) {
        };
        Map<String, String[]> map = request.getParameterMap();
        assertNotNull(map);
        assertEquals(1, map.size());
        assertEquals("val", map.get("key")[0]);
        ctx.close();
    }

    @Test
    public void testRequestGetHost() throws Exception {
        ChannelContext ctx = new ChannelContext(SocketChannel.open(), 4096);
        Map<String, Object> headers = new HashMap<String, Object>();
        headers.put("Host", "example.com");
        HttpDecodedRequest request = new HttpDecodedRequest(HttpVersion.HTTP_1_1, headers, ctx) {
        };
        assertEquals("example.com", request.getHost());
        ctx.close();
    }
}

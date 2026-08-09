package io.github.wycst.wastnet.http.handler;

import io.github.wycst.wastnet.http.HttpMethod;
import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.http.HttpStatus;
import io.github.wycst.wastnet.http.handler.HttpWelcomeHandler;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link HttpWelcomeHandler}.
 *
 * @author wangyc
 */
public class HttpWelcomeHandlerTest {

    @Test
    public void testHandleSetsStatus200() throws Throwable {
        HttpWelcomeHandler handler = new HttpWelcomeHandler();
        final HttpStatus[] capturedStatus = {null};
        handler.handle(MockHttpTestBase.mockRequest(HttpMethod.GET, "/"),
                MockHttpTestBase.mockResponse(capturedStatus, (String[]) null, null));
        Assertions.assertEquals(HttpStatus.OK, capturedStatus[0]);
    }

    @Test
    public void testHandleSetsContentTypeHtml() throws Throwable {
        HttpWelcomeHandler handler = new HttpWelcomeHandler();
        final String[] capturedContentType = {null};
        handler.handle(MockHttpTestBase.mockRequest(HttpMethod.GET, "/"),
                MockHttpTestBase.mockResponse(null, capturedContentType, null));
        Assertions.assertNotNull(capturedContentType[0]);
        Assertions.assertTrue(capturedContentType[0].contains("text/html"), capturedContentType[0]);
    }

    @Test
    public void testHandleSetsBodyContent() throws Throwable {
        HttpWelcomeHandler handler = new HttpWelcomeHandler();
        final byte[][] capturedBody = {null};
        handler.handle(MockHttpTestBase.mockRequest(HttpMethod.GET, "/"),
                MockHttpTestBase.mockResponse(null, (String[]) null, capturedBody));
        Assertions.assertNotNull(capturedBody[0]);
        Assertions.assertTrue(capturedBody[0].length > 0, "Body should not be empty");
        String bodyStr = new String(capturedBody[0], java.nio.charset.StandardCharsets.UTF_8);
        Assertions.assertTrue(bodyStr.contains("wastnet"), bodyStr);
    }

    @Test
    public void testTemplateContainsFeatureList() throws Throwable {
        HttpWelcomeHandler handler = new HttpWelcomeHandler();
        final byte[][] capturedBody = {null};
        handler.handle(MockHttpTestBase.mockRequest(HttpMethod.GET, "/"),
                MockHttpTestBase.mockResponse(null, (String[]) null, capturedBody));
        String bodyStr = new String(capturedBody[0], java.nio.charset.StandardCharsets.UTF_8);
        Assertions.assertTrue(bodyStr.contains("WebSocket"), bodyStr);
    }

    @Test
    public void testTemplateContainsQuickStartCode() throws Throwable {
        HttpWelcomeHandler handler = new HttpWelcomeHandler();
        final byte[][] capturedBody = {null};
        handler.handle(MockHttpTestBase.mockRequest(HttpMethod.GET, "/"),
                MockHttpTestBase.mockResponse(null, (String[]) null, capturedBody));
        String bodyStr = new String(capturedBody[0], java.nio.charset.StandardCharsets.UTF_8);
        Assertions.assertTrue(bodyStr.contains("HTTPServer.of(8080)"), bodyStr);
        Assertions.assertTrue(bodyStr.contains("Hello World"), bodyStr);
    }
}

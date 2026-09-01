package io.github.wycst.wastnet.http.handler;

import io.github.wycst.wastnet.http.HTTPServer;
import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.http.HttpStatus;
import io.github.wycst.wastnet.util.Utils;

/**
 * welcome page
 *
 * @since 2024-1-23
 * @author wangyc
 */
public class HttpWelcomeHandler implements HttpRequestHandler {

    // Static HTML template
    private static final String HTML_TEMPLATE = buildTemplate();

    private static String buildTemplate() {
        return "<!DOCTYPE html>\n" +
                "<html lang=\"zh-CN\">\n" +
                "<head>\n" +
                "<meta charset=\"UTF-8\">\n" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "<title>wastnet</title>\n" +
                "<style>\n" +
                "* { margin:0; padding:0; box-sizing:border-box; }\n" +
                "body { font-family:sans-serif; background:#f5f5f5; color:#333; padding:40px 20px; display:flex; justify-content:center; }\n" +
                ".c { max-width:600px; width:100%; }\n" +
                ".card { background:#fff; border-radius:8px; box-shadow:0 2px 8px rgba(0,0,0,0.08); padding:24px; margin-bottom:16px; }\n" +
                "h1 { font-size:1.6em; font-weight:500; }\n" +
                ".tag { display:inline-block; background:#f0f0f0; color:#666; border-radius:4px; padding:2px 8px; font-size:12px; margin:2px; }\n" +
                "pre { background:#f5f5f5; padding:12px; border-radius:4px; font-size:13px; overflow-x:auto; }\n" +
                "a { color:#1565c0; text-decoration:none; }\n" +
                ".f { text-align:center; font-size:13px; color:#999; margin-top:16px; }\n" +
                "</style>\n" +
                "</head>\n" +
                "<body>\n" +
                "<div class=\"c\">\n" +
                "  <div class=\"card\">\n" +
                "    <h1>wastnet <span style=\"font-size:14px;color:#999;font-weight:400;\">v" + HTTPServer.VERSION + "</span></h1>\n" +
                "    <p style=\"margin-top:8px;\">\n" +
                "      <span class=\"tag\">HTTP/1.1</span>\n" +
                "      <span class=\"tag\">HTTP/2</span>\n" +
                "      <span class=\"tag\">H2C</span>\n" +
                "      <span class=\"tag\">WebSocket</span>\n" +
                "      <span class=\"tag\">SSE</span>\n" +
                "    </p>\n" +
                "  </div>\n" +
                "  <div class=\"card\">\n" +
                "    <pre>HTTPServer.of(8080)\n" +
                "    .requestHandler((req, res) ->\n" +
                "        res.body(\"Hello World!\".getBytes()))\n" +
                "    .start();</pre>\n" +
                "  </div>\n" +
                "  <div class=\"f\">\n" +
                "    <a href=\"https://github.com/wycst02/wastnet\" target=\"_blank\">GitHub</a>\n" +
                "  </div>\n" +
                "</div>\n" +
                "</body>\n" +
                "</html>";
    }

    @Override
    public void handle(HttpRequest request, HttpResponse response) throws Throwable {
        // Send the static HTML template
        response.status(HttpStatus.OK)
                .contentType("text/html;charset=utf-8")
                .body(HTML_TEMPLATE.getBytes(Utils.UTF_8));
    }
}
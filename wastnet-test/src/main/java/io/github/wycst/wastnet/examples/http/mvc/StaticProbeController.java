package io.github.wycst.wastnet.examples.http.mvc;

import io.github.wycst.wastnet.http.annotation.ContentType;
import io.github.wycst.wastnet.http.annotation.Endpoint;
import io.github.wycst.wastnet.http.annotation.RestController;

@RestController
public class StaticProbeController {

    // 预生成 23KB HTML,仅构建一次(无 DB、无每请求字符串拼接)
    private static final String FORTUNES_HTML = build();

    private static String build() {
        StringBuilder sb = new StringBuilder(24 * 1024);
        sb.append("<!DOCTYPE html><html><head><title>Fortunes</title></head><body><table>");
        for (int i = 0; sb.length() < 23 * 1024; i++) {
            sb.append("<tr><td>").append(i)
              .append("</td><td>fortune message number ")
              .append(i).append(" for benchmark isolation test</td></tr>");
        }
        sb.append("</table></body></html>");
        return sb.toString();
    }

    @Endpoint(value = "/fortunes", responseType = ContentType.HTML)
    public String fortunes() {
        return FORTUNES_HTML;
    }

    // 每次请求都重新拼接 HTML(对比 /fortunes 的静态返回,用于隔离"构建开销"基准)
    @Endpoint(value = "/fortunes-built", responseType = ContentType.HTML)
    public String fortunesBuilt() {
        return build();
    }
}
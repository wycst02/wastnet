package io.github.wycst.wastnet.benchmarks.http.uri;

import io.github.wycst.wastnet.util.Utils;

/**
 * URI编码正确性验证 & 性能测试
 */
public class UriEncodeBenchmarkTest {

    private static final int WARMUP_ITERATIONS = 1000;
    private static final int MEASURE_ITERATIONS = 100000;

    static class TestCase {
        final String input;
        final String expected;

        TestCase(String input, String expected) {
            this.input = input;
            this.expected = expected;
        }
    }

    private static final TestCase[] TEST_CASES = {
            // 纯ASCII安全字符 - fast-path原样返回
            new TestCase("/api/users/list", "/api/users/list"),
            // 含中文: /api/用户/列表
            new TestCase("/api/\u7528\u6237/\u5217\u8868", "/api/%E7%94%A8%E6%88%B7/%E5%88%97%E8%A1%A8"),
            // 中文+query: /api/用户/详情?id=123
            new TestCase("/api/\u7528\u6237/\u8BE6\u60C5?id=123", "/api/%E7%94%A8%E6%88%B7/%E8%AF%A6%E6%83%85?id=123"),
            // 含空格
            new TestCase("/path/with spaces/and/more", "/path/with%20spaces/and/more"),
            // 长路径+中文+多query参数: /api/数据分析/报告?type=daily&date=2024-01-01
            new TestCase("/api/\u6570\u636E/\u5206\u6790/\u62A5\u544A?type=daily&date=2024-01-01",
                    "/api/%E6%95%B0%E6%8D%AE/%E5%88%86%E6%9E%90/%E6%8A%A5%E5%91%8A?type=daily&date=2024-01-01"),
            // 需编码的ASCII特殊字符: < > " + % [ ] { } | \ ^
            new TestCase("/path/<tag>", "/path/%3Ctag%3E"),
            new TestCase("/path/\"quote\"", "/path/%22quote%22"),
            new TestCase("/path/a+b", "/path/a%2Bb"),
            new TestCase("/path/100%", "/path/100%25"),
            new TestCase("/path/[id]", "/path/%5Bid%5D"),
            new TestCase("/path/{name}", "/path/%7Bname%7D"),
            new TestCase("/path/a|b", "/path/a%7Cb"),
            new TestCase("/path/a\\b", "/path/a%5Cb"),
            new TestCase("/path/a^b", "/path/a%5Eb"),
            // Emoji / 代理对: 😀 U+1F600
            new TestCase("/emoji/\uD83D\uDE00", "/emoji/%F0%9F%98%80"),
            // 日文: /api/データ
            new TestCase("/api/\u30C7\u30FC\u30BF", "/api/%E3%83%87%E3%83%BC%E3%82%BF"),
            // 韩文: /api/데이터
            new TestCase("/api/\uB370\uC774\uD130", "/api/%EB%8D%B0%EC%9D%B4%ED%84%B0"),
            // 多个query参数
            new TestCase("/search?q=hello&lang=cn&page=1", "/search?q=hello&lang=cn&page=1"),
            // 带fragment
            new TestCase("/page#section1", "/page#section1"),
            // 混合: 安全字符 + 空格 + 中文 + 特殊字符
            new TestCase("/api/\u7528\u6237/list?name=Zhang San&age=20",
                    "/api/%E7%94%A8%E6%88%B7/list?name=Zhang%20San&age=20"),
            // 保留字符不应被编码: / ? = & #
            new TestCase("/path?key=val&key2=val2#frag", "/path?key=val&key2=val2#frag"),
            // unreserved字符不应被编码: - _ . ~
            new TestCase("/file/my-doc_v2.0~backup", "/file/my-doc_v2.0~backup"),
            // 空字符串
            new TestCase("", ""),
            // 仅斜杠
            new TestCase("/", "/"),
            // 连续中文
            new TestCase("/\u4F60\u597D\u4E16\u754C", "/%E4%BD%A0%E5%A5%BD%E4%B8%96%E7%95%8C"),
            // tab字符编码
            new TestCase("/path\ta", "/path%09a"),
            // 换行符编码
            new TestCase("/path\na", "/path%0Aa"),
            // 中文query值
            new TestCase("/search?q=\u4F60\u597D", "/search?q=%E4%BD%A0%E5%A5%BD"),

            // === 以下为新增20种场景 ===
            // 连续多个空格
            new TestCase("/a  b", "/a%20%20b"),
            // %本身需要编码: /path/100%
            new TestCase("/path/100%", "/path/100%25"),
            // 路径中的.和.. (safe字符不编码)
            new TestCase("/a/../b", "/a/../b"),
            // Latin扩展字符: é (U+00E9)
            new TestCase("/caf\u00E9", "/caf%C3%A9"),
            // Latin扩展字符: ñ (U+00F1)
            new TestCase("/se\u00F1or", "/se%C3%B1or"),
            // Latin扩展字符: ü (U+00FC)
            new TestCase("/\u00FCber", "/%C3%BCber"),
            // 感叹号 !
            new TestCase("/hello!", "/hello%21"),
            // 星号 *
            new TestCase("/*.txt", "/%2A.txt"),
            // 圆括号 ( )
            new TestCase("/(test)", "%28test%29"),
            // 逗号 ,
            new TestCase("/a,b", "/a%2Cb"),
            // 分号 ; (常见于URL参数分隔)
            new TestCase("/path;a=1", "/path%3Ba=1"),
            // 冒号 :
            new TestCase("/path:a", "/path%3Aa"),
            // @符号
            new TestCase("/path@host", "/path%40host"),
            // 美元符号 $
            new TestCase("/$var", "/%24var"),
            // 反引号 `
            new TestCase("/path`test", "/path%60test"),
            // 单引号 '
            new TestCase("/path'name", "/path%27name"),
            // Fragment中含中文: /page#标题
            new TestCase("/page#\u6807\u9898", "/page#%E6%A0%87%E9%A2%98"),
            // 长中文路径: /网站/产品/分类/详情
            new TestCase("/\u7F51\u7AD9/\u4EA7\u54C1/\u5206\u7C7B/\u8BE6\u60C5",
                    "/%E7%BD%91%E7%AB%99/%E4%BA%A7%E5%93%81/%E5%88%86%E7%B1%BB/%E8%AF%A6%E6%83%85"),
            // 连续特殊字符
            new TestCase("/<<<>>>", "/%3C%3C%3C%3E%3E%3E"),
            // 纯特殊字符输入
            new TestCase("<>", "%3C%3E"),
    };

    public static void main(String[] args) {
        System.out.println("=== URI Encode Correctness & Performance Test ===\n");

        // 1. 正确性测试
        int passCount = 0;
        int failCount = 0;
        for (TestCase tc : TEST_CASES) {
            String actual = Utils.encodeUriPath(tc.input);
            boolean pass = tc.expected.equals(actual);
            if (pass) {
                passCount++;
            } else {
                failCount++;
                System.out.println("[FAIL] Input:    " + tc.input);
                System.out.println("       Expected: " + tc.expected);
                System.out.println("       Actual:   " + actual);
            }
        }

        System.out.println("\n--- Correctness Summary ---");
        System.out.println("Total: " + TEST_CASES.length + " | Pass: " + passCount + " | Fail: " + failCount);
        if (failCount == 0) {
            System.out.println("All tests PASSED!");
        }

        // 2. 性能测试
        System.out.println("\n--- Performance Benchmark ---");
        System.out.println("Warmup: " + WARMUP_ITERATIONS + " | Measure: " + MEASURE_ITERATIONS + "\n");

        String[] perfUris = {
                "/api/users/list",
                "/api/\u7528\u6237/\u5217\u8868",
                "/api/\u7528\u6237/\u8BE6\u60C5?id=123",
                "/api/\u6570\u636E/\u5206\u6790/\u62A5\u544A?type=daily&date=2024-01-01",
        };

        for (String uri : perfUris) {
            long time = benchmark(uri, new Runnable() {
                public void run() {
                    Utils.encodeUriPath(uri);
                }
            });
            System.out.println("Input: " + uri);
            System.out.println("  Utils.encodeUriPath: " + time + " ms\n");
        }
    }

    private static long benchmark(String name, Runnable task) {
        for (int i = 0; i < WARMUP_ITERATIONS; ++i) {
            task.run();
        }
        long start = System.nanoTime();
        for (int i = 0; i < MEASURE_ITERATIONS; ++i) {
            task.run();
        }
        return (System.nanoTime() - start) / 1_000_000;
    }
}

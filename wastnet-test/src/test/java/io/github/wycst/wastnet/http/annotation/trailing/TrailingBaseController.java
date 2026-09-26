package io.github.wycst.wastnet.http.annotation.trailing;

import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.http.annotation.Controller;
import io.github.wycst.wastnet.http.annotation.Endpoint;

/**
 * Fixture for {@code testRouterScanTrailingBaseResolvesClientUri}: a controller whose base path
 * carries a trailing slash, used to verify combinePath normalization through the real scan flow.
 */
@Controller("/api/")
public class TrailingBaseController {

    public static volatile boolean invoked = false;

    @Endpoint("/users")
    public void users(HttpRequest req, HttpResponse resp) {
        invoked = true;
    }
}

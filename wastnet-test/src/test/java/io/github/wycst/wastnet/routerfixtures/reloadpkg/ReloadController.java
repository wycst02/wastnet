package io.github.wycst.wastnet.routerfixtures.reloadpkg;

import io.github.wycst.wastnet.http.annotation.Controller;
import io.github.wycst.wastnet.http.annotation.Endpoint;

/**
 * Fixture controller for the dev hot-reload test: scanned through a fresh
 * {@code RestartClassLoader} on every {@code reload()}.
 */
@Controller("/rl")
public class ReloadController {

    @Endpoint("/ping")
    public void ping() {
    }
}

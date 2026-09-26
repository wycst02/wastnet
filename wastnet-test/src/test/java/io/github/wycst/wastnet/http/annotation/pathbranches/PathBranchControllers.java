package io.github.wycst.wastnet.http.annotation.pathbranches;

import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.http.annotation.Controller;
import io.github.wycst.wastnet.http.annotation.Endpoint;

/**
 * Fixtures covering every {@code combinePath} branch through the real scan flow, since
 * {@code combinePath} is now private and cannot be unit-tested directly.
 */
public class PathBranchControllers {

    // base == "" -> first branch, returns path (with leading slash)
    @Controller("")
    public static class EmptyBase {
        @Endpoint("/empty")
        public void e(HttpRequest r, HttpResponse s) {}
    }

    // base == "/" -> first branch, returns path (with leading slash)
    @Controller("/")
    public static class RootBase {
        @Endpoint("/root")
        public void r(HttpRequest r, HttpResponse s) {}
    }

    // path == "/" -> second branch, returns normalized base
    @Controller("/pSlash")
    public static class PathSlash {
        @Endpoint("/")
        public void s(HttpRequest r, HttpResponse s) {}
    }

    // path without leading slash -> prepend "/"
    @Controller("/pNoLead")
    public static class PathNoLeading {
        @Endpoint("sub")
        public void s(HttpRequest r, HttpResponse s) {}
    }
}

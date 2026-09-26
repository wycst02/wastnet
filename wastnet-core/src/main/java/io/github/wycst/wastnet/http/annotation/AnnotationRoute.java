package io.github.wycst.wastnet.http.annotation;

import io.github.wycst.wastnet.http.handler.HttpRoute;

/**
 * Marker interface for routes produced by package scanning. Used by hot reload to drop only scanned routes,
 * keeping manually-registered routes intact.
 */
public interface AnnotationRoute extends HttpRoute {
}

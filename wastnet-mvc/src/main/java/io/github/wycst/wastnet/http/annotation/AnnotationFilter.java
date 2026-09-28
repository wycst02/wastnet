package io.github.wycst.wastnet.http.annotation;

/**
 * Filter for accepting scanned classes during package scanning.
 * Implementations determine whether a linked (but not yet initialized)
 * class should be retained in the scan result.
 *
 * @author wangyc
 */
public interface AnnotationFilter {

    /**
     * Returns {@code true} if the given class should be included in the scan result.
     *
     * @param clazz the class to check (already linked but NOT initialized)
     * @return true to keep the class, false to discard
     */
    boolean accept(Class<?> clazz);
}

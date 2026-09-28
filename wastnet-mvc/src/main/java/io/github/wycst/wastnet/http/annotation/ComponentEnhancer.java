package io.github.wycst.wastnet.http.annotation;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;

/**
 * Hook for @Component proxy wrapping. The framework uses proxyAnnotations to
 * decide automatically whether to wrap, and enhance builds the proxy instance.
 * Applies to constructed @Component and @Controller instances; @Bean factory results are
 * excluded.
 *
 * @author wangyc
 */
public interface ComponentEnhancer {

    /**
     * Aspect annotations that trigger proxying: if the class or any of its
     * methods is annotated with one of them, wrapping is triggered. Defaults
     * to {@code null} (no automatic trigger).
     *
     * @return the annotation types, e.g. {@code new Class[]{Transactional.class}}, or {@code null}
     */
    default Class<? extends Annotation>[] proxyAnnotations() {
        return null;
    }

    /**
     * Whether a proxy should be created for the component; default false.
     * Override for custom judgment (the framework still adds the proxyAnnotations check).
     *
     * @param componentClass the original {@code @Component} class
     * @return true to wrap the component
     */
    default boolean requiresProxy(Class<?> componentClass) {
        return false;
    }

    /**
     * Create a proxy/enhanced object for the component. The framework resolves
     * the constructor and its arguments and passes them so the enhancer can
     * instantiate the target if its strategy requires it (e.g. CGLIB calling
     * super, or a delegate target via ctor.newInstance). Returning an object
     * not assignable to componentClass fails registration.
     *
     * @param componentClass   the original {@code @Component} class
     * @param constructor      the resolved constructor used to build the target
     * @param constructorArgs  the resolved constructor arguments (possibly empty)
     * @return the object to register (typically a proxy around the target)
     */
    Object enhance(Class<?> componentClass, Constructor<?> constructor, Object[] constructorArgs);
}

package io.github.wycst.wastnet.http.annotation;

/**
 * Internal record for a registered bean. Keeps the original component/controller
 * class (sourceClass) distinct from the live instance so field injection can target
 * the declared fields even when the instance is a proxy (CGLIB subclass of sourceClass).
 */
final class BeanDefinition {
    final Class<?> sourceClass;
    final Object instance;
    final boolean singleton;
    final boolean proxy;

    BeanDefinition(Class<?> sourceClass, Object instance, boolean singleton, boolean proxy) {
        this.sourceClass = sourceClass;
        this.instance = instance;
        this.singleton = singleton;
        this.proxy = proxy;
    }

    Object getInstance() {
        return instance;
    }

    Class<?> getSourceClass() {
        return sourceClass;
    }
}

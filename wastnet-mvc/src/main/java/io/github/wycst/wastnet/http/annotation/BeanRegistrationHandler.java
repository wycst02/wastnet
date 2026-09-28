package io.github.wycst.wastnet.http.annotation;

/**
 * Implementations are discovered during package scanning and, when their bound
 * {@link Registration#value()} is in the {@link AnnotationRouterHandler#enables}
 * white-list, are instantiated and handed a {@link RegistrarContext} so they
 * can register third-party beans into the container.
 *
 * @author wangyc
 */
public interface BeanRegistrationHandler {

    /**
     * Registers beans into the container via the given context. Invoked once per scan,
     * after the scan configuration is loaded and BEFORE component / {@code @Bean}
     * instantiation — so scanned beans can {@code @Inject} what this registrar provides.
     * Read configuration and prepare state here (the former separate {@code init} step is
     * folded into this single call).
     *
     * @param context the registrar context
     */
    void onRegister(RegistrarContext context);

    /**
     * Invoked once after all beans are injected and their {@code @PostConstruct} methods
     * executed, i.e. the container is fully ready. Suitable for startup work that depends
     * on other beans being initialized. Default implementation is a no-op.
     *
     * @param context the registrar context
     */
    default void onAllReady(RegistrarContext context) {}

    /**
     * Optional cleanup hook, invoked on hot reload or container clear.
     * Default implementation is a no-op.
     */
    default void onDestroy() {}
}

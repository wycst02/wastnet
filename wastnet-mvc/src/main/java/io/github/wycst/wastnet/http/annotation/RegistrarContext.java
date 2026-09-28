package io.github.wycst.wastnet.http.annotation;

import java.util.Map;

/**
 * Registration surface exposed to a {@link BeanRegistrationHandler}.
 * Decouples handlers from the concrete {@code BeanContainer} implementation and
 * provides read access to application configuration.
 *
 * @author wangyc
 */
public interface RegistrarContext {

    /**
     * Registers a bean under the given name.
     *
     * @param name the registration name used for lookup
     * @param instance the singleton instance
     */
    void registerBean(String name, Object instance);

    /**
     * Registers a bean under the given type.
     *
     * @param <T> the registered type
     * @param type the type to register the instance under
     * @param instance the singleton instance (must be assignable to {@code type})
     * @throws IllegalArgumentException if the instance is not assignable to {@code type}
     */
    <T> void registerBean(Class<T> type, Object instance);

    /**
     * Looks up a registered bean by type.
     *
     * @param <T> the bean type
     * @param type the type to look up
     * @return the matching bean, or {@code null} if absent
     */
    <T> T getBean(Class<T> type);

    /**
     * Looks up a registered bean by name.
     *
     * @param name the registration name
     * @return the matching bean, or {@code null} if absent
     */
    Object getBean(String name);

    /**
     * Resolves a configuration value by key, honouring system properties, scan configuration and
     * static configuration (in descending priority).
     *
     * @param key the configuration key
     * @return the value, or {@code null} if not present
     */
    String getConfig(String key);

    /**
     * Resolves a configuration value by key, falling back to {@code def} when absent.
     *
     * @param key the configuration key
     * @param def the fallback value
     * @return the value, or {@code def} if not present
     */
    String getConfig(String key, String def);

    /**
     * Dynamically contributes a configuration value, visible to subsequent {@code @Value} resolution.
     * Cleared on each re-scan, so the active registrar re-evaluates it every scan.
     *
     * @param key the configuration key
     * @param value the configuration value
     */
    void addProperty(String key, String value);

    /**
     * Dynamically contributes multiple configuration values at once, visible to subsequent {@code @Value} resolution.
     * Cleared on each re-scan, so the active registrar re-evaluates them every scan.
     *
     * @param props the key-value pairs to add
     */
    void addProperties(Map<String, String> props);
}

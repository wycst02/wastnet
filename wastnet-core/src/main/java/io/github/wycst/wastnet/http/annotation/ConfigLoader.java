package io.github.wycst.wastnet.http.annotation;

import java.util.Map;

/**
 * Pluggable configuration loader.
 * <p>
 * Implement this interface to load configuration from custom sources
 * (e.g. YAML, remote config center, database).
 *
 * @author wangyc
 */
public interface ConfigLoader {
    /**
     * Load configuration entries into the given map.
     *
     * @param config mutable configuration map to populate
     */
    void load(Map<String, String> config);
}

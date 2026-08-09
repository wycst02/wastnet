package testonly.retrybeans;

import io.github.wycst.wastnet.http.annotation.Bean;
import io.github.wycst.wastnet.http.annotation.Component;
import io.github.wycst.wastnet.http.annotation.Configuration;
import io.github.wycst.wastnet.http.annotation.Inject;
import io.github.wycst.wastnet.http.annotation.Value;

/**
 * Test configuration classes for deferred @Bean method retry coverage.
 * Placed under a completely separate package so full-scans of
 * "io.github.wycst.wastnet.http.annotation" do NOT pick them up.
 */
public final class BeanDeferredConfig {

    private BeanDeferredConfig() {}

    @Configuration
    @Component
    public static class DeferredConfig {
        @Bean
        public String firstBean() { return "first"; }
        @Bean
        public String secondBean(@Inject("firstBean") String f) { return f + "+second"; }
        @Bean
        public String valueBean(@Value("helloValue") String val) { return val; }
    }

    @Configuration
    @Component
    public static class ExhaustRetryConfig {
        @Bean
        public String neverResolved(@Inject("nonExistent") String dep) { return dep; }
    }
}

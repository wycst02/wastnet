package testonly.valuebean;

import io.github.wycst.wastnet.http.annotation.*;

/**
 * Fixture: a {@code @Configuration} whose {@code @Bean} method reads an {@code @Value} field.
 * Used to verify the field is injected before any {@code @Bean} method runs during scan.
 */
public class ValueBeanConfig {

    @Configuration
    @Component
    public static class ValueBeforeBeanConfig {
        @Value("${service.url:default-url}") private String url;

        @Bean
        public MyService myService() {
            // reads the @Value field; proves it was injected before this @Bean ran
            return new MyService(url);
        }

        public String getUrl() { return url; }
    }

    public static class MyService {
        public final String url;
        public MyService(String url) { this.url = url; }
    }
}

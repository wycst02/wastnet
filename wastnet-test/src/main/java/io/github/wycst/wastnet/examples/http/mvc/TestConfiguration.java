package io.github.wycst.wastnet.examples.http.mvc;


import io.github.wycst.wastnet.http.annotation.Bean;
import io.github.wycst.wastnet.http.annotation.Configuration;

@Configuration
public class TestConfiguration {
    @Bean
    public CreateUserReq createUserReq() {
        return new CreateUserReq();
    }
}

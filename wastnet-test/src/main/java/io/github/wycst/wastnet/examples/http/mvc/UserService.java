package io.github.wycst.wastnet.examples.http.mvc;

import io.github.wycst.wastnet.http.annotation.Component;
import io.github.wycst.wastnet.http.annotation.PostConstruct;
import io.github.wycst.wastnet.http.annotation.PreDestroy;
import io.github.wycst.wastnet.http.annotation.Value;

/**
 * 托管组件 — 演示 {@code @Component}、{@code @Value}、{@code @PostConstruct}、{code @PreDestroy}.
 *
 * @author wangyc
 */
@Component
public class UserService {

    @Value("${app.prefix:User-}")
    private String namePrefix;

    public UserService() {
        System.out.println("[DI] constructor: UserService");
    }

    @PostConstruct
    public void init() {
        System.out.println("[DI] @PostConstruct: prefix=" + namePrefix);
    }

    @PreDestroy
    public void close() {
        System.out.println("[DI] @PreDestroy: closing");
    }

    public String getUserName(int id) {
        return namePrefix + id;
    }

    public String listUsers() {
        return "alice, bob, charlie";
    }
}

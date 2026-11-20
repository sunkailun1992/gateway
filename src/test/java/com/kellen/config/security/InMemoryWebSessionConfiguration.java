package com.kellen.config.security;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.session.MapSession;
import org.springframework.session.ReactiveMapSessionRepository;
import org.springframework.session.ReactiveSessionRepository;
import org.springframework.session.config.annotation.web.server.EnableSpringWebSession;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 测试用的内存会话：Spring Session 的 {@link ReactiveMapSessionRepository}（Spring Session 文档的 WebFlux 写法），
 * 线上同一套 Spring Session 接 Redis。用到它的测试要排除 Redis 会话自动配置。
 *
 * @author 孙凯伦
 */
@TestConfiguration(proxyBeanMethods = false)
@EnableSpringWebSession
public class InMemoryWebSessionConfiguration {

    /**
     * 内存会话仓库。
     *
     * @return 会话仓库
     */
    @Bean
    ReactiveSessionRepository<MapSession> reactiveSessionRepository() {
        return new ReactiveMapSessionRepository(new ConcurrentHashMap<>());
    }
}

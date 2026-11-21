package com.kellen.config.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 延迟的客户端注册仓库：第一次查找时才构建（照 Spring Security 的 {@code SupplierReactiveJwtDecoder}），成功的结果一直用，
 * 失败不缓存、下次再试；构建（按 issuer-uri 取发现文档是阻塞调用）放在 boundedElastic 线程上，不占 Netty 事件循环。
 */
class SupplierReactiveClientRegistrationRepositoryTest {

    @Test
    void buildsOnFirstLookupAndKeepsTheResult() {
        AtomicInteger builds = new AtomicInteger();
        SupplierReactiveClientRegistrationRepository repository = new SupplierReactiveClientRegistrationRepository(() -> {
            builds.incrementAndGet();
            return new InMemoryReactiveClientRegistrationRepository(registration());
        });
        assertThat(builds).hasValue(0);
        assertThat(repository.findByRegistrationId("keycloak").block(Duration.ofSeconds(5))).isNotNull();
        assertThat(repository.findByRegistrationId("keycloak").block(Duration.ofSeconds(5))).isNotNull();
        assertThat(builds).hasValue(1);
    }

    @Test
    void failureIsNotCachedAndSurfacesAsUnavailable() {
        AtomicInteger builds = new AtomicInteger();
        SupplierReactiveClientRegistrationRepository repository = new SupplierReactiveClientRegistrationRepository(() -> {
            if (builds.incrementAndGet() == 1) {
                throw new IllegalArgumentException("Unable to resolve Configuration with the provided Issuer");
            }
            return new InMemoryReactiveClientRegistrationRepository(registration());
        });
        assertThatThrownBy(() -> repository.findByRegistrationId("keycloak").block(Duration.ofSeconds(5)))
                .isInstanceOf(ClientRegistrationUnavailableException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
        assertThat(repository.findByRegistrationId("keycloak").block(Duration.ofSeconds(5))).isNotNull();
        assertThat(builds).hasValue(2);
    }

    @Test
    void discoveryRunsOffTheEventLoop() {
        AtomicReference<String> thread = new AtomicReference<>();
        SupplierReactiveClientRegistrationRepository repository = new SupplierReactiveClientRegistrationRepository(() -> {
            thread.set(Thread.currentThread().getName());
            return new InMemoryReactiveClientRegistrationRepository(registration());
        });
        repository.findByRegistrationId("keycloak").block(Duration.ofSeconds(5));
        assertThat(thread.get()).startsWith("boundedElastic");
    }

    private static ClientRegistration registration() {
        return ClientRegistration.withRegistrationId("keycloak").clientId("github-gateway").clientSecret("fixture")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("http://127.0.0.1:1/auth").tokenUri("http://127.0.0.1:1/token").build();
    }
}

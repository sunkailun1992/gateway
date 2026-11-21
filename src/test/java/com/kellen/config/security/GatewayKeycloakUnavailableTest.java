package com.kellen.config.security;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keycloak 不可达时网关照常启动（与各服务的资源服务器一致）：客户端注册在第一次用到时才按 issuer-uri 发现，
 * 发现失败不缓存；不可达期间登录入口回 503 JSON（C0001），Keycloak 恢复后不用重启就能登录。
 * 以前 Spring Boot 在启动时就去取发现文档，NAS 链路断着网关就起不来。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.name=gateway-keycloak-unavailable-isolated", "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.config.import-check.enabled=false", "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.service-registry.auto-registration.enabled=false", "management.health.redis.enabled=false",
        "server.address=127.0.0.1",
        "spring.autoconfigure.exclude=org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration",
        "spring.cloud.gateway.server.webflux.default-filters[0]=TokenRelay"})
@ActiveProfiles("exposure-it")
@Import(InMemoryWebSessionConfiguration.class)
class GatewayKeycloakUnavailableTest {

    private static final FakeKeycloak KEYCLOAK = FakeKeycloak.get();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // 上下文创建（启动）时发现文档就连不上。
        KEYCLOAK.reset();
        KEYCLOAK.dropDiscovery();
        FakeKeycloak.register(registry);
    }

    @AfterAll
    static void restore() {
        KEYCLOAK.reset();
    }

    @Test
    void startsWithoutKeycloakAnswers503AndLogsInOnceKeycloakIsBack() {
        WebTestClient client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).responseTimeout(Duration.ofSeconds(20)).build();
        client.get().uri("/oauth2/authorization/keycloak").accept(MediaType.TEXT_HTML).exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.success").isEqualTo(false).jsonPath("$.code").isEqualTo("C0001");
        KEYCLOAK.reset();
        client.get().uri("/oauth2/authorization/keycloak").accept(MediaType.TEXT_HTML).exchange()
                .expectStatus().isFound()
                .expectHeader().value("Location", location -> assertThat(location).startsWith(KEYCLOAK.issuer() + "/protocol/openid-connect/auth?"));
    }
}

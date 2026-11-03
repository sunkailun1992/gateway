package com.kellen.config;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文档开启（等同 dev/test 默认）时，Swagger 聚合与后端文档照常可用，Actuator 仍然不对外。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"gateway.exposure.api-docs-enabled=true",
                "spring.config.name=exposure-isolated", "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.nacos.config.import-check.enabled=false", "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.service-registry.auto-registration.enabled=false",
                "management.endpoints.web.exposure.include=*", "management.endpoint.shutdown.access=unrestricted",
                "management.health.redis.enabled=false"})
@ActiveProfiles("exposure-it")
class ApiDocsEnabledIntegrationTest {

    private static final StubBackend BACKEND = StubBackend.start();

    @LocalServerPort
    private int port;

    private WebTestClient client;

    @DynamicPropertySource
    static void reportRoute(DynamicPropertyRegistry registry) {
        BACKEND.registerReportRoute(registry);
    }

    @AfterAll
    static void stopBackend() {
        BACKEND.stop();
    }

    @BeforeEach
    void setUp() {
        BACKEND.clear();
        client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
    }

    @Test
    void swaggerConfigIsServedWhenEnabled() {
        client.get().uri("/v3/api-docs/swagger-config").exchange().expectStatus().isOk();
    }

    @Test
    void backendApiDocsAreForwardedWhenEnabled() {
        client.get().uri("/report/v3/api-docs").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("stub:/v3/api-docs");
    }

    @Test
    void actuatorStaysBlockedWhenDocsEnabled() {
        client.get().uri("/actuator/env").exchange().expectStatus().isNotFound();
        client.get().uri("/report/actuator/env").exchange().expectStatus().isNotFound();
        assertThat(BACKEND.receivedPaths()).isEmpty();
    }
}

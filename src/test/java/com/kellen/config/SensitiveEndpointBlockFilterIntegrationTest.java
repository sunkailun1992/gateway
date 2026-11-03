package com.kellen.config;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文档关闭（等同 prod 默认）时，走真实网关链路验证 Actuator 与 OpenAPI 入口不再对外，业务转发不受影响。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"gateway.exposure.api-docs-enabled=false",
                "spring.config.name=exposure-isolated", "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.nacos.config.import-check.enabled=false", "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.service-registry.auto-registration.enabled=false",
                "management.endpoints.web.exposure.include=*", "management.endpoint.shutdown.access=unrestricted",
                "management.health.redis.enabled=false"})
@ActiveProfiles("exposure-it")
class SensitiveEndpointBlockFilterIntegrationTest {

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

    @ParameterizedTest
    @ValueSource(strings = {"/actuator", "/actuator/env", "/actuator/beans", "/actuator/configprops",
            "/actuator/mappings", "/actuator/nacosdiscovery", "/actuator/nacosconfig"})
    void gatewayActuatorEndpointsAreNotExposed(String path) {
        client.get().uri(path).exchange().expectStatus().isNotFound();
    }

    @Test
    void shutdownRequestDoesNotStopGateway() {
        client.post().uri("/actuator/shutdown").exchange().expectStatus().isNotFound();
        client.get().uri("/actuator/health").exchange().expectStatus().isOk();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"})
    void gatewayHealthStaysReachableForProbes(String path) {
        client.get().uri(path).exchange().expectStatus().value(status -> assertThat(status).isNotEqualTo(404));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/report/actuator", "/report/actuator/env", "/report/actuator/nacosconfig",
            "/report/actuator/shutdownGraceFul", "/report/actuator/health"})
    void backendActuatorIsBlockedBeforeForwarding(String path) {
        client.get().uri(path).exchange().expectStatus().isNotFound();
        assertThat(BACKEND.receivedPaths()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/report/Actuator/env", "/report/actuator;v=1/env", "/report/actuator/",
            "/actuator;v=1/env"})
    void pathSpellingVariantsAreMatchedLikeRouting(String rawPath) {
        client.get().uri(URI.create("http://127.0.0.1:" + port + rawPath)).exchange()
                .expectStatus().isNotFound();
        assertThat(BACKEND.receivedPaths()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/v3/api-docs", "/v3/api-docs/swagger-config", "/v3/api-docs.yaml",
            "/swagger-ui.html", "/swagger-ui/index.html", "/webjars/swagger-ui/index.html",
            "/report/v3/api-docs"})
    void apiDocsAreNotExposedWhenDisabled(String path) {
        client.get().uri(path).exchange().expectStatus().isNotFound();
        assertThat(BACKEND.receivedPaths()).isEmpty();
    }

    @Test
    void businessRequestsAreStillForwarded() {
        client.get().uri("/report/assessments/1").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("stub:/assessments/1");
    }
}

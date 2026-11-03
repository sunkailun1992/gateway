package com.kellen.config;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实网关 HTTP / stub backend 验证显式 CORS allowlist，不连接 Nacos 或真实业务服务。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.name=cors-policy-isolated", "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.config.import-check.enabled=false", "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.service-registry.auto-registration.enabled=false", "management.health.redis.enabled=false"
})
class CorsPolicyHttpIntegrationTest {
    private static final String ADMIN = "https://admin.example.test";
    private static final StubBackend BACKEND = StubBackend.start();
    @LocalServerPort private int port;
    @Autowired private ConfigurableEnvironment environment;
    private WebTestClient client;

    @DynamicPropertySource
    static void route(DynamicPropertyRegistry registry) { BACKEND.registerReportRoute(registry); }

    @AfterAll
    static void stopBackend() { BACKEND.stop(); }

    @BeforeEach
    void setup() {
        BACKEND.clear();
        client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
        policy(Map.of("gateway.cors.allowed-origins", ADMIN, "gateway.cors.allow-credentials", true));
    }

    @Test
    void allowedPreflightUsesExactOriginAndExplicitHeadersWithoutCallingBackend() {
        client.options().uri("/report/records").header("Origin", ADMIN)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,content-type,x-trace-id,x-traffic-lane")
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", ADMIN)
                .expectHeader().valueEquals("Access-Control-Allow-Credentials", "true")
                .expectHeader().value("Access-Control-Allow-Headers", value ->
                        assertThat(value.toLowerCase()).contains("authorization", "content-type", "x-trace-id", "x-traffic-lane")
                                .doesNotContain("*"));
        assertThat(BACKEND.receivedPaths()).isEmpty();
    }

    @Test
    void unlistedAndNullOriginsAreRejectedBeforeRouting() {
        for (String origin : new String[]{"https://evil.example.test", "null", "https://admin.example.test.evil.test"}) {
            client.options().uri("/report/records").header("Origin", origin)
                    .header("Access-Control-Request-Method", "POST").exchange()
                    .expectStatus().isForbidden().expectHeader().doesNotExist("Access-Control-Allow-Origin");
            client.get().uri("/report/records").header("Origin", origin).exchange()
                    .expectStatus().isForbidden().expectHeader().doesNotExist("Access-Control-Allow-Origin");
        }
        assertThat(BACKEND.receivedPaths()).isEmpty();
    }

    @Test
    void credentialedWildcardAndMalformedConfiguredOriginsNeverPermitRequests() {
        for (String configured : new String[]{"*", "https://*.example.test", "null", "https://admin.example.test/path", "https://user:secret@admin.example.test"}) {
            policy(Map.of("gateway.cors.allowed-origins", configured, "gateway.cors.allow-credentials", true));
            client.options().uri("/report/records").header("Origin", ADMIN)
                    .header("Access-Control-Request-Method", "GET").exchange()
                    .expectStatus().isForbidden().expectHeader().doesNotExist("Access-Control-Allow-Origin");
        }
        assertThat(BACKEND.receivedPaths()).isEmpty();
    }

    @Test
    void emptyAllowlistDeniesCrossOriginButDoesNotChangeOrdinaryRouting() {
        policy(Map.of());
        client.options().uri("/report/records").header("Origin", ADMIN)
                .header("Access-Control-Request-Method", "GET").exchange()
                .expectStatus().isForbidden().expectHeader().doesNotExist("Access-Control-Allow-Origin");
        client.get().uri("/report/records").exchange().expectStatus().isOk()
                .expectHeader().doesNotExist("Access-Control-Allow-Origin");
        assertThat(BACKEND.receivedPaths()).containsExactly("/records");
    }

    @Test
    void allowedActualAndSameOriginRequestsKeepBusinessRouting() {
        client.get().uri("/report/records").header("Origin", ADMIN)
                .header("Authorization", "Bearer synthetic-browser-token").exchange()
                .expectStatus().isOk().expectHeader().valueEquals("Access-Control-Allow-Origin", ADMIN);
        client.get().uri("/report/records").header("Origin", "http://127.0.0.1:" + port).exchange()
                .expectStatus().isOk().expectHeader().doesNotExist("Access-Control-Allow-Origin");
        assertThat(BACKEND.receivedPaths()).containsExactly("/records", "/records");
    }

    @Test
    void unlistedMethodAndHeaderAreRejectedRatherThanReflected() {
        client.options().uri("/report/records").header("Origin", ADMIN)
                .header("Access-Control-Request-Method", "TRACE").exchange().expectStatus().isForbidden();
        client.options().uri("/report/records").header("Origin", ADMIN)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "X-User-Id").exchange().expectStatus().isForbidden();
        assertThat(BACKEND.receivedPaths()).isEmpty();
    }

    @Test
    void explicitMethodAndHeaderPolicyOverridesDefaultsWithoutImplicitCredentials() {
        policy(Map.of("gateway.cors.allowed-origins", ADMIN, "gateway.cors.allow-credentials", false,
                "gateway.cors.allowed-methods", "PUT", "gateway.cors.allowed-headers", "X-Custom-Client"));
        client.options().uri("/report/records").header("Origin", ADMIN)
                .header("Access-Control-Request-Method", "PUT")
                .header("Access-Control-Request-Headers", "X-Custom-Client").exchange()
                .expectStatus().isOk().expectHeader().valueEquals("Access-Control-Allow-Origin", ADMIN)
                .expectHeader().doesNotExist("Access-Control-Allow-Credentials");
        client.options().uri("/report/records").header("Origin", ADMIN)
                .header("Access-Control-Request-Method", "POST").exchange().expectStatus().isForbidden();
        client.options().uri("/report/records").header("Origin", ADMIN)
                .header("Access-Control-Request-Method", "PUT")
                .header("Access-Control-Request-Headers", "Authorization").exchange().expectStatus().isForbidden();
        assertThat(BACKEND.receivedPaths()).isEmpty();
    }

    @Test
    void wildcardIsNotAnAllowlistEvenWithoutCredentialsAndDoesNotBlockSameOrigin() {
        policy(Map.of("gateway.cors.allowed-origins", "*", "gateway.cors.allow-credentials", false));
        client.options().uri("/report/records").header("Origin", ADMIN)
                .header("Access-Control-Request-Method", "GET").exchange()
                .expectStatus().isForbidden().expectHeader().doesNotExist("Access-Control-Allow-Origin");
        client.get().uri("/report/records").exchange().expectStatus().isOk();
        client.get().uri("/report/records").header("Origin", "http://127.0.0.1:" + port).exchange()
                .expectStatus().isOk().expectHeader().doesNotExist("Access-Control-Allow-Origin");
    }

    private void policy(Map<String, Object> values) {
        environment.getPropertySources().addFirst(new MapPropertySource("cors-test-policy", values));
    }
}

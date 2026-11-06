package com.kellen.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.test.web.reactive.server.WebTestClient;

/** 未配置运维令牌时独立采集失败关闭。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.name=observability-isolated", "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.config.import-check.enabled=false", "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.service-registry.auto-registration.enabled=false", "management.server.port=0",
        "server.address=127.0.0.1", "management.server.address=127.0.0.1",
        "management.endpoints.web.exposure.include=*", "management.health.redis.enabled=false",
        "security.actuator.token="})
class PrometheusUnconfiguredTokenIntegrationTest {
    @LocalManagementPort int managementPort;
    @Test void noConfigurationNeverAcceptsAnyToken() {
        var client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + managementPort).build();
        client.get().uri("/actuator/prometheus").exchange().expectStatus().isUnauthorized();
        client.get().uri("/actuator/prometheus").header("actuatorReq", "arbitrary")
                .exchange().expectStatus().isUnauthorized();
        client.get().uri("/actuator/env").header("actuatorReq", "arbitrary")
                .exchange().expectStatus().isNotFound();
    }
}

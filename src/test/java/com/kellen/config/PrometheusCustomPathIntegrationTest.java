package com.kellen.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.reactive.server.WebTestClient;

/** 使用真实独立管理端口验证采集与公网边界，令牌仅为合成测试值。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.name=observability-isolated", "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.config.import-check.enabled=false", "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.service-registry.auto-registration.enabled=false", "management.server.port=0",
        "server.address=127.0.0.1", "management.server.address=127.0.0.1",
        "management.endpoints.web.exposure.include=*", "management.endpoints.web.base-path=/ops",
        "management.endpoints.web.path-mapping.prometheus=scrape", "management.health.redis.enabled=false",
        "security.actuator.token=synthetic-actuator-token"})
class PrometheusCustomPathIntegrationTest {
    @LocalServerPort int port;
    @LocalManagementPort int managementPort;

    @Test void scrapeRequiresValidTokenAndServesPrometheus() {
        client(managementPort).get().uri("/ops/scrape").exchange().expectStatus().isUnauthorized();
        client(managementPort).get().uri("/ops/scrape").header("actuatorReq", "wrong")
                .exchange().expectStatus().isUnauthorized();
        client(managementPort).get().uri("/ops/scrape").header("actuatorReq", "synthetic-actuator-token")
                .exchange().expectStatus().isOk().expectBody(String.class)
                .value(body -> org.assertj.core.api.Assertions.assertThat(body).contains("jvm_memory_used_bytes"));
    }

    @Test void applicationPortAndForwardedHeadersCannotExposeManagement() {
        for (String path : new String[]{"/ops/scrape", "/report/ops/scrape"}) {
            client(port).get().uri(path).header("actuatorReq", "synthetic-actuator-token")
                    .header("X-Forwarded-Port", Integer.toString(managementPort))
                    .exchange().expectStatus().isNotFound();
        }
    }

    @Test void sensitiveEndpointsStayClosedEvenWithToken() {
        for (String path : new String[]{"/ops/env", "/ops/configprops", "/ops/heapdump"}) {
            client(managementPort).get().uri(path).header("actuatorReq", "synthetic-actuator-token")
                    .exchange().expectStatus().isNotFound();
        }
    }

    private WebTestClient client(int targetPort) {
        return WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + targetPort).build();
    }
}

package com.kellen.config;

import com.kellen.traffic.CanaryGrant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.net.URI;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/** 通过真实网关和负载均衡验证稳定、灰度及拒绝路径。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.name=exposure-isolated", "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.config.import-check.enabled=false", "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.service-registry.auto-registration.enabled=false", "management.health.redis.enabled=false",
        "traffic.governance.canary.enabled=true",
        "spring.cloud.gateway.server.webflux.loadbalancer.use404=true"
})
@ActiveProfiles("exposure-it")
class CanaryRoutingIntegrationTest {

    private static final String SIGNING_KEY = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
    private static final StubBackend STABLE = StubBackend.start();
    private static final StubBackend CANARY = StubBackend.start();

    @LocalServerPort
    private int port;

    private WebTestClient client;

    @DynamicPropertySource
    static void configureRoutes(DynamicPropertyRegistry registry) {
        String discovery = "spring.cloud.discovery.client.simple.instances.";
        registry.add(discovery + "report[0].uri", STABLE::uri);
        registry.add(discovery + "report-canary-blue[0].uri", CANARY::uri);
        registry.add("traffic.governance.canary.signing-key", () -> SIGNING_KEY);
        registry.add("traffic.governance.canary.routes.report.blue", () -> "report-canary-blue");
        registry.add("traffic.governance.canary.routes.report.absent", () -> "report-canary-absent");
        registry.add("traffic.governance.canary.routes.report.invalid", () -> "user");
        registry.add("traffic.governance.canary.routes.user.blue", () -> "user-canary-blue");
        registerRoute(registry, 0, "report", "/report/**", "lb://report");
        registerRoute(registry, 1, "user", "/user/**", "lb://user");
        registerRoute(registry, 2, "static", "/static/**", STABLE.uri());
        registerRoute(registry, 3, "direct", "/direct/**", "lb://report-canary-blue");
    }

    private static void registerRoute(DynamicPropertyRegistry registry, int index,
                                      String id, String path, String uri) {
        String route = "spring.cloud.gateway.server.webflux.routes[" + index + "]";
        registry.add(route + ".id", () -> id);
        registry.add(route + ".uri", () -> uri);
        registry.add(route + ".predicates[0]", () -> "Path=" + path);
        if ("report".equals(id)) {
            registry.add(route + ".filters[0]", () -> "RewritePath=/report/(?<segment>.*), /$\\{segment}");
        }
    }

    @AfterAll
    static void stopBackends() {
        STABLE.stop();
        CANARY.stop();
    }

    @BeforeEach
    void setUp() {
        STABLE.clear();
        CANARY.clear();
        client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
    }

    @Test
    void usesStableServiceAndRemovesUntrustedGovernanceHeaders() {
        client.get().uri("/report/current")
                .header(HttpHeaders.AUTHORIZATION, "Bearer fixture")
                .header("X-Tenant-Id", "fixture-tenant")
                .header("X-Release-Version", "external-version")
                .header("X-Traffic-Lane", "external-lane")
                .header("X-Canary-Tag", "blue")
                .header("X-Canary-Weight", "100")
                .header("dubbo-tag", "blue")
                .header("dubbo.tag", "blue")
                .header("dubbo.force.tag", "true")
                .exchange().expectStatus().isOk().expectBody(String.class).isEqualTo("stub:/current");

        assertThat(STABLE.receivedRequests()).singleElement().satisfies(request -> {
            assertThat(request.headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer fixture");
            assertThat(request.headers().getFirst("X-Tenant-Id")).isEqualTo("fixture-tenant");
            assertThat(request.headers().getFirst("X-Release-Version")).isEqualTo("1.0.0");
            assertThat(request.headers().getFirst("X-Traffic-Lane")).isEqualTo("stable");
            assertThat(request.headers().containsHeader("X-Canary-Tag")).isFalse();
            assertThat(request.headers().containsHeader("X-Canary-Weight")).isFalse();
            assertThat(request.headers().containsHeader("dubbo-tag")).isFalse();
            assertThat(request.headers().containsHeader("dubbo.tag")).isFalse();
            assertThat(request.headers().containsHeader("dubbo.force.tag")).isFalse();
            assertThat(request.headers().containsHeader(CanaryGrant.HEADER)).isFalse();
        });
        assertThat(CANARY.receivedRequests()).isEmpty();
    }

    @Test
    void routesVerifiedGrantToCanaryAndPreservesEncodedPathQueryAndAuthorization() {
        String token = grant("report", "blue");
        client.get().uri(URI.create("http://127.0.0.1:" + port + "/report/a%20b?part=a%2Fb&part=c%2Bd"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer fixture")
                .header(CanaryGrant.HEADER, token)
                .header("X-Traffic-Lane", "external-lane")
                .header("X-Canary-Tag", "external-tag")
                .exchange().expectStatus().isOk().expectBody(String.class).isEqualTo("stub:/a%20b");

        assertThat(CANARY.receivedRequests()).singleElement().satisfies(request -> {
            assertThat(request.target()).isEqualTo("/a%20b?part=a%2Fb&part=c%2Bd");
            assertThat(request.headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer fixture");
            assertThat(request.headers().get(CanaryGrant.HEADER)).containsExactly(token);
            assertThat(request.headers().containsHeader("X-Traffic-Lane")).isFalse();
            assertThat(request.headers().containsHeader("X-Canary-Tag")).isFalse();
        });
        assertThat(STABLE.receivedRequests()).isEmpty();
    }

    @Test
    void rejectsUnverifiedDuplicateAndOutOfScopeGrantsBeforeForwarding() {
        String token = grant("report", "blue");
        client.get().uri("/report/current").header(CanaryGrant.HEADER, token + "invalid")
                .exchange().expectStatus().isForbidden();
        client.get().uri("/report/current").header(CanaryGrant.HEADER, token, token)
                .exchange().expectStatus().isForbidden();
        client.get().uri("/user/current").header(CanaryGrant.HEADER, token)
                .exchange().expectStatus().isForbidden();
        client.get().uri("/static/current").header(CanaryGrant.HEADER, token)
                .exchange().expectStatus().isForbidden();
        client.get().uri("/report/current").header(CanaryGrant.HEADER, grant("report", "unknown"))
                .exchange().expectStatus().isForbidden();
        client.get().uri("/report/current").header(CanaryGrant.HEADER, grant("report", "invalid"))
                .exchange().expectStatus().isForbidden();
        client.get().uri("/direct/current").exchange().expectStatus().isForbidden();

        assertThat(STABLE.receivedRequests()).isEmpty();
        assertThat(CANARY.receivedRequests()).isEmpty();
    }

    @Test
    void returnsUnavailableWhenCanaryIsAbsentWithoutFallingBack() {
        client.get().uri("/report/current").header(CanaryGrant.HEADER, grant("report", "absent"))
                .exchange().expectStatus().isEqualTo(503);

        assertThat(STABLE.receivedRequests()).isEmpty();
        assertThat(CANARY.receivedRequests()).isEmpty();
    }

    private static String grant(String service, String tag) {
        return CanaryGrant.issue(service, tag, Instant.now().plusSeconds(60), SIGNING_KEY);
    }
}

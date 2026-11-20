package com.kellen.config;

import com.kellen.config.security.GatewaySecurityConfig;
import com.kellen.config.security.FakeKeycloak;
import com.kellen.traffic.CanaryGrant;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.adapter.ForwardedHeaderTransformer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 用真实网关与内嵌后端验证 dev 本机默认灰度；不连接 Nacos，不解析业务身份。 */
@SpringBootTest(classes = LocalCanaryIngressIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.name=local-canary-ingress-it",
        "spring.config.location=optional:classpath:/local-canary-ingress-it/",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.config.import-check.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.service-registry.auto-registration.enabled=false",
        "spring.cloud.gateway.server.webflux.loadbalancer.use404=true",
        "management.health.redis.enabled=false", "server.address=127.0.0.1",
        "server.forward-headers-strategy=none",
        "local-canary.ready=true", "local-canary.tag=kellen-local",
        "traffic.governance.canary.enabled=true"
})
@ActiveProfiles({"dev", "exposure-it", "local-canary-ingress-it"})
class LocalCanaryIngressIntegrationTest {

    private static final String KEY = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
    private static final String TAG = "kellen-local";
    private static final Backend STABLE = Backend.start();
    private static final Backend USER = Backend.start();
    private static final Backend AI = Backend.start();
    private static final String BEARER = "Bearer " + FakeKeycloak.get().bearerToken();

    @LocalServerPort int port;
    private WebTestClient client;

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry registry) {
        FakeKeycloak.register(registry);
        String discovery = "spring.cloud.discovery.client.simple.instances.";
        registry.add(discovery + "user[0].uri", STABLE::uri);
        registry.add(discovery + "ai[0].uri", STABLE::uri);
        registry.add(discovery + "absent[0].uri", STABLE::uri);
        registry.add(discovery + "user-canary-kellen-local[0].uri", USER::uri);
        registry.add(discovery + "ai-canary-kellen-local[0].uri", AI::uri);
        registry.add("traffic.governance.canary.signing-key", () -> KEY);
        for (String service : List.of("user", "ai", "absent")) {
            registry.add("traffic.governance.canary.routes." + service + "." + TAG,
                    () -> service + "-canary-" + TAG);
        }
        route(registry, 0, "auth", "/auth/**", "lb://user");
        route(registry, 1, "ai", "/api/**", "lb://ai");
        route(registry, 2, "absent", "/absent/**", "lb://absent");
        route(registry, 3, "direct", "/direct/**", "lb://ai-canary-kellen-local");
        route(registry, 4, "static", "/static/**", STABLE.uri());
    }

    private static void route(DynamicPropertyRegistry registry, int index, String id, String path, String uri) {
        String key = "spring.cloud.gateway.server.webflux.routes[" + index + "]";
        registry.add(key + ".id", () -> id);
        registry.add(key + ".uri", () -> uri);
        registry.add(key + ".predicates[0]", () -> "Path=" + path);
    }

    @BeforeEach void prepare() {
        STABLE.received.clear(); USER.received.clear(); AI.received.clear();
        client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).defaultHeader(HttpHeaders.AUTHORIZATION, BEARER).build();
    }

    @AfterAll static void stop() { STABLE.stop(); USER.stop(); AI.stop(); }

    @Test void localSessionWithoutExternalGrantUsesUserCanaryAndPreservesOpaqueBusinessRequest() {
        byte[] body = "{\"opaque\":\"fixture-session-body\"}".getBytes(StandardCharsets.UTF_8);
        forwardAndAssert("/auth/miniapp/session?part=a%2Fb&part=c%2Bd", "user", USER, body,
                MediaType.APPLICATION_JSON);
        assertThat(AI.received).isEmpty();
    }

    @Test void localDocumentWithoutExternalGrantUsesAiCanaryAndPreservesMultipartBytes() {
        byte[] body = ("--fixture\r\nContent-Disposition: form-data; name=\"file\"; filename=\"fixture.png\"\r\n"
                + "Content-Type: image/png\r\n\r\nSYNTHETIC_IMAGE_BYTES\r\n--fixture--\r\n")
                .getBytes(StandardCharsets.UTF_8);
        forwardAndAssert("/api/ai/patient-document/ocr?part=a%2Fb&part=c%2Bd", "ai", AI, body,
                MediaType.parseMediaType("multipart/form-data; boundary=fixture"));
        assertThat(USER.received).isEmpty();
    }

    private void forwardAndAssert(String target, String service, Backend backend, byte[] body, MediaType contentType) {
        Instant before = Instant.now();
        client.post().uri(URI.create("http://127.0.0.1:" + port + target))
                .contentType(contentType)
                .header("tenant-id", "fixture-tenant").header("X-Kellen-Source-Tenant-Id", "fixture-source")
                .header("X-Meilian-Access-Token", "Bearer fixture-saas-token")
                .header("X-Request-Id", "fixture-request").bodyValue(body)
                .exchange().expectStatus().isOk();
        Instant after = Instant.now();
        assertThat(STABLE.received).isEmpty();
        assertThat(backend.received).singleElement().satisfies(request -> {
            assertThat(request.target()).isEqualTo(target);
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.body()).containsExactly(body);
            assertThat(request.headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo(BEARER);
            assertThat(request.headers().getFirst("tenant-id")).isEqualTo("fixture-tenant");
            assertThat(request.headers().getFirst("X-Kellen-Source-Tenant-Id")).isEqualTo("fixture-source");
            assertThat(request.headers().getFirst("X-Meilian-Access-Token")).isEqualTo("Bearer fixture-saas-token");
            assertThat(request.headers().getFirst("X-Request-Id")).isEqualTo("fixture-request");
            assertThat(request.headers().getContentType()).isEqualTo(contentType);
            assertThat(request.headers().get(CanaryGrant.HEADER)).hasSize(1);
            var grant = CanaryGrant.verify(request.headers().getFirst(CanaryGrant.HEADER), KEY, after);
            assertThat(grant).isNotNull();
            assertThat(grant.service()).isEqualTo(service);
            assertThat(grant.tag()).isEqualTo(TAG);
            assertThat(grant.expiresAt().getEpochSecond())
                    .isBetween(before.getEpochSecond() + 44, after.getEpochSecond() + 45);
            assertThat(request.headers().containsHeader("X-Traffic-Lane")).isFalse();
            assertThat(request.headers().containsHeader("X-Release-Version")).isFalse();
            assertThat(request.headers().containsHeader("X-Canary-Tag")).isFalse();
        });
    }

    @Test void businessAuthenticationRemainsBackendResponsibility() {
        client.post().uri("/auth/miniapp/session").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("opaque-fixture-body-without-business-credentials")
                .exchange().expectStatus().isOk();
        assertThat(USER.received).hasSize(1);
        assertThat(STABLE.received).isEmpty();
    }

    @Test void explicitValidGrantIsPreservedInsteadOfReissued() {
        String token = CanaryGrant.issue("user", TAG, Instant.now().plusSeconds(30), KEY);
        client.get().uri("/auth/current").header(CanaryGrant.HEADER, token)
                .exchange().expectStatus().isOk();
        assertThat(USER.received).singleElement().satisfies(request ->
                assertThat(request.headers().get(CanaryGrant.HEADER)).containsExactly(token));
        assertThat(STABLE.received).isEmpty();
    }

    @Test void invalidDuplicateAndMismatchedExplicitGrantIsRejectedWithoutLocalReplacement() {
        String token = CanaryGrant.issue("user", TAG, Instant.now().plusSeconds(30), KEY);
        client.get().uri("/auth/current").header(CanaryGrant.HEADER, token + "invalid")
                .exchange().expectStatus().isForbidden();
        client.get().uri("/auth/current").header(CanaryGrant.HEADER, token, token)
                .exchange().expectStatus().isForbidden();
        client.get().uri("/api/ai/patient-document/ocr").header(CanaryGrant.HEADER, token)
                .exchange().expectStatus().isForbidden();
        assertNoBackendRequest();
    }

    @Test void missingCanaryReturns503AndNeverFallsBackToAvailableStableInstance() {
        client.get().uri("/absent/current").exchange().expectStatus().isEqualTo(503);
        assertNoBackendRequest();
    }

    @Test void foreignHostAndOriginAreRejectedBeforeForwarding() {
        client.get().uri("/auth/current").header(HttpHeaders.HOST, "foreign.fixture.invalid")
                .exchange().expectStatus().isForbidden();
        client.get().uri("/auth/current").header(HttpHeaders.ORIGIN, "https://foreign.fixture.invalid")
                .exchange().expectStatus().isForbidden();
        assertNoBackendRequest();
    }

    @Test void directCanaryServiceRouteRemainsForbidden() {
        client.get().uri("/direct/current").exchange().expectStatus().isForbidden();
        assertNoBackendRequest();
    }

    @Test void staticNonLoadBalancedRouteDoesNotAcquireLocalGrant() {
        client.get().uri("/static/current").exchange().expectStatus().isOk();
        assertThat(STABLE.received).singleElement().satisfies(request ->
                assertThat(request.headers().containsHeader(CanaryGrant.HEADER)).isFalse());
        assertThat(USER.received).isEmpty(); assertThat(AI.received).isEmpty();
    }

    @Test void defaultIngressBeanExistsOnlyForDevWithoutTestOrProd() {
        for (String[] profiles : List.of(new String[]{"dev"}, new String[]{"test"}, new String[]{"prod"},
                new String[]{"dev", "test"}, new String[]{"dev", "prod"})) {
            boolean enabled = profiles.length == 1 && profiles[0].equals("dev");
            new ApplicationContextRunner().withUserConfiguration(ProfileConfiguration.class)
                    .withInitializer(context -> context.getEnvironment().setActiveProfiles(profiles))
                    .run(context -> assertThat(context.getBeanNamesForType(LocalCanaryIngress.class))
                            .hasSize(enabled ? 1 : 0));
        }
    }

    @Test void actualSocketLocalityCannotBeForgedByForwardedHeaders() {
        var ingress = ingress(properties(), environment());
        for (String[] addresses : List.of(new String[]{"192.0.2.1", "127.0.0.1"},
                new String[]{"127.0.0.1", "192.0.2.2"})) {
            for (boolean spoof : List.of(false, true)) {
                var request = MockServerHttpRequest.get("/auth/current")
                        .localAddress(new InetSocketAddress(addresses[0], 7100))
                        .remoteAddress(new InetSocketAddress(addresses[1], 41234))
                        .header(HttpHeaders.HOST, "127.0.0.1:7100");
                if (spoof) request.header("Forwarded", "for=127.0.0.1;host=localhost")
                        .header("X-Forwarded-For", "127.0.0.1").header("X-Forwarded-Host", "localhost");
                assertStatus(ingress, MockServerWebExchange.from(request.build()), HttpStatus.FORBIDDEN);
            }
        }
    }

    @Test void loopbackSocketsDoNotMakeForwardedHeadersTrusted() {
        var ingress = ingress(properties(), environment());
        for (String name : List.of("Forwarded", "X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto",
                "X-Forwarded-Port", "X-Forwarded-Prefix", "X-Forwarded-Server")) {
            assertStatus(ingress, exchange(headers(name, "fixture-forwarded-value")), HttpStatus.FORBIDDEN);
        }
    }

    @Test void forwardingStrategiesAndCustomTransformerDisableImplicitLocalAuthority() {
        for (String strategy : List.of("framework", "native")) {
            assertStatus(ingress(properties(), environment().withProperty("server.forward-headers-strategy", strategy)),
                    exchange(new HttpHeaders()), HttpStatus.SERVICE_UNAVAILABLE);
        }
        assertStatus(ingress(properties(), environment(), new ForwardedHeaderTransformer()),
                exchange(new HttpHeaders()), HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test void duplicateHostAndAmbiguousOrForeignOriginAreRejected() {
        var ingress = ingress(properties(), environment());
        for (HttpHeaders headers : List.of(headers(HttpHeaders.HOST, "127.0.0.1", "localhost"),
                headers(HttpHeaders.HOST, "localhost@foreign.fixture.invalid"),
                headers(HttpHeaders.ORIGIN, "http://localhost", "https://localhost"),
                headers(HttpHeaders.ORIGIN, "null"), headers(HttpHeaders.ORIGIN, "https://foreign.fixture.invalid"),
                headers(HttpHeaders.ORIGIN, "http://localhost/path"))) {
            assertStatus(ingress, exchange(headers), HttpStatus.FORBIDDEN);
        }
    }

    @Test void missingReadinessKeyTagMappingOrLoopbackBindingFailsClosed() {
        for (String mode : List.of("ready", "enabled", "key", "missing-key", "tag", "mapping", "binding")) {
            var properties = properties(); var environment = environment();
            switch (mode) {
                case "ready" -> environment.setProperty("local-canary.ready", "false");
                case "enabled" -> properties.getCanary().setEnabled(false);
                case "key" -> properties.getCanary().setSigningKey("invalid-fixture-key");
                case "missing-key" -> properties.getCanary().setSigningKey(null);
                case "tag" -> environment.setProperty("local-canary.tag", "stable");
                case "mapping" -> properties.getCanary().setRoutes(Map.of());
                case "binding" -> environment.setProperty("server.address", "0.0.0.0");
            }
            assertStatus(ingress(properties, environment), exchange(new HttpHeaders()),
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private static GatewayTrafficGovernanceProperties properties() {
        var properties = new GatewayTrafficGovernanceProperties();
        properties.getCanary().setEnabled(true); properties.getCanary().setSigningKey(KEY);
        properties.getCanary().setRoutes(Map.of("user", Map.of(TAG, "user-canary-" + TAG)));
        return properties;
    }

    private static MockEnvironment environment() {
        return new MockEnvironment().withProperty("server.address", "127.0.0.1")
                .withProperty("server.forward-headers-strategy", "none")
                .withProperty("local-canary.ready", "true").withProperty("local-canary.tag", TAG);
    }

    private static LocalCanaryIngress ingress(GatewayTrafficGovernanceProperties properties,
                                              MockEnvironment environment, ForwardedHeaderTransformer... transformers) {
        var factory = new StaticListableBeanFactory();
        for (int index = 0; index < transformers.length; index++) factory.addBean("fixture-transformer-" + index, transformers[index]);
        return new LocalCanaryIngress(properties, environment, factory.getBeanProvider(ForwardedHeaderTransformer.class));
    }

    private static HttpHeaders headers(String key, String... values) {
        var headers = new HttpHeaders(); headers.addAll(key, List.of(values)); return headers;
    }

    private static MockServerWebExchange exchange(HttpHeaders headers) {
        var all = new HttpHeaders(); all.add(HttpHeaders.HOST, "127.0.0.1:7100"); all.putAll(headers);
        return MockServerWebExchange.from(MockServerHttpRequest.get("/auth/current").headers(all)
                .localAddress(new InetSocketAddress("127.0.0.1", 7100))
                .remoteAddress(new InetSocketAddress("127.0.0.1", 41234)).build());
    }

    private static void assertStatus(LocalCanaryIngress ingress, MockServerWebExchange exchange, HttpStatus status) {
        assertThatThrownBy(() -> ingress.grant(exchange, "user"))
                .isInstanceOfSatisfying(ResponseStatusException.class, failure ->
                        assertThat(failure.getStatusCode()).isEqualTo(status));
    }

    private static void assertNoBackendRequest() {
        assertThat(STABLE.received).isEmpty(); assertThat(USER.received).isEmpty(); assertThat(AI.received).isEmpty();
    }

    @Configuration(proxyBeanMethods = false)
    @Profile("local-canary-ingress-it")
    @EnableAutoConfiguration
    @Import({GatewayTrafficGovernanceProperties.class, TrafficGovernanceHeaderFilter.class,
            CanaryRoutingFilter.class, LocalCanaryIngress.class, GatewaySecurityConfig.class})
    static class TestApplication { }

    @TestConfiguration(proxyBeanMethods = false)
    @Import({GatewayTrafficGovernanceProperties.class, LocalCanaryIngress.class})
    static class ProfileConfiguration { }

    /** 本文件专用的真实 HTTP 桩同时保留原始字节，避免修改已有测试后端。 */
    private static final class Backend {
        private final HttpServer server;
        private final List<Received> received = new CopyOnWriteArrayList<>();

        private Backend(HttpServer server) { this.server = server; }

        static Backend start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                Backend backend = new Backend(server);
                server.createContext("/", exchange -> {
                    var headers = new HttpHeaders(); exchange.getRequestHeaders().forEach(headers::addAll);
                    backend.received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                            HttpHeaders.readOnlyHttpHeaders(headers), exchange.getRequestBody().readAllBytes()));
                    byte[] response = "fixture-backend".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    try (var output = exchange.getResponseBody()) { output.write(response); }
                    finally { exchange.close(); }
                });
                server.start(); return backend;
            } catch (IOException failure) { throw new UncheckedIOException(failure); }
        }

        String uri() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        void stop() { server.stop(0); }
    }

    private record Received(String method, String target, HttpHeaders headers, byte[] body) { }
}

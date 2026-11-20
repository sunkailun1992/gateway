package com.kellen.config.security;

import com.nimbusds.jose.JWSAlgorithm;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.client.web.server.WebSessionServerOAuth2AuthorizedClientRepository;
import org.springframework.session.MapSession;
import org.springframework.session.ReactiveSessionRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 网关 BFF 的真实 HTTP 测试：浏览器经 Keycloak 授权码加 PKCE 登录，会话在 Spring Session（测试用内存仓库），转发时
 * TokenRelay 把会话里的访问令牌换成 Bearer 头、去掉 Cookie 头；脚本直接带 Bearer 令牌时校验后原样转发。
 * Keycloak 与下游服务都用 MockWebServer 模拟，令牌用测试密钥签名。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.name=gateway-security-isolated", "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.config.import-check.enabled=false", "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.service-registry.auto-registration.enabled=false", "management.health.redis.enabled=false",
        "server.address=127.0.0.1",
        "spring.autoconfigure.exclude=org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration",
        "spring.cloud.gateway.server.webflux.default-filters[0]=TokenRelay",
        "spring.cloud.gateway.server.webflux.default-filters[1]=RemoveRequestHeader=Cookie",
        "gateway.exposure.api-docs-enabled=true", "springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=true",
        "springdoc.swagger-ui.csrf.enabled=true"})
@ActiveProfiles("exposure-it")
@Import(InMemoryWebSessionConfiguration.class)
class GatewaySecurityConfigTest {

    private static final FakeKeycloak KEYCLOAK = FakeKeycloak.get();

    private static final MockWebServer DOWNSTREAM = downstream();

    private static final String SESSION = "__Host-SESSION";

    private static final String XSRF = "XSRF-TOKEN";

    @LocalServerPort
    private int port;

    @Autowired
    private ReactiveSessionRepository<MapSession> sessions;

    private WebTestClient client;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        FakeKeycloak.register(registry);
        String route = "spring.cloud.gateway.server.webflux.routes[0]";
        registry.add(route + ".id", () -> "report");
        registry.add(route + ".uri", () -> "http://127.0.0.1:" + DOWNSTREAM.getPort());
        registry.add(route + ".predicates[0]", () -> "Path=/report/**");
        registry.add(route + ".filters[0]", () -> "StripPrefix=1");
    }

    @AfterAll
    static void stop() throws Exception {
        DOWNSTREAM.shutdown();
    }

    @BeforeEach
    void setUp() throws Exception {
        KEYCLOAK.reset();
        while (DOWNSTREAM.takeRequest(0, TimeUnit.MILLISECONDS) != null) {
            // 清掉上一个用例留下的下游请求。
        }
        client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).responseTimeout(Duration.ofSeconds(20)).build();
    }

    @Test
    void loginUsesPkceAndKeepsTheSessionCookieHostOnlySecureHttpOnlyAndLax() throws Exception {
        EntityExchangeResult<byte[]> start = client.get().uri("/oauth2/authorization/keycloak").exchange()
                .expectStatus().isFound().expectBody().returnResult();
        URI authorize = start.getResponseHeaders().getLocation();
        assertThat(authorize).isNotNull();
        assertThat(authorize.toString()).startsWith(KEYCLOAK.issuer() + "/protocol/openid-connect/auth?");
        MultiValueMap<String, String> query = UriComponentsBuilder.fromUri(authorize).build().getQueryParams();
        assertThat(query.getFirst("response_type")).isEqualTo("code");
        assertThat(query.getFirst("client_id")).isEqualTo(FakeKeycloak.CLIENT_ID);
        assertThat(query.getFirst("code_challenge_method")).isEqualTo("S256");
        assertThat(decode(query.getFirst("code_challenge"))).hasSize(43);
        assertThat(decode(query.getFirst("redirect_uri"))).isEqualTo("http://127.0.0.1:" + port + "/login/oauth2/code/keycloak");
        assertThat(query.getFirst("nonce")).isNotBlank();
        ResponseCookie session = start.getResponseCookies().getFirst(SESSION);
        assertThat(session).isNotNull();
        assertThat(session.isSecure()).isTrue();
        assertThat(session.isHttpOnly()).isTrue();
        assertThat(session.getSameSite()).isEqualTo("Lax");
        assertThat(session.getPath()).isEqualTo("/");
        assertThat(session.getDomain()).isNull();
    }

    @Test
    void callbackExchangesTheCodeWithItsVerifierAndChangesTheSessionId() throws Exception {
        Login login = login();
        assertThat(login.session()).isNotEqualTo(login.preLoginSession());
        assertThat(KEYCLOAK.tokenRequests()).singleElement().satisfies(request -> {
            assertThat(request.get("grant_type")).isEqualTo("authorization_code");
            assertThat(request.get("code")).isEqualTo(FakeKeycloak.CODE);
            assertThat(s256(request.get("code_verifier"))).isEqualTo(login.challenge());
            assertThat(request.get("authorization")).startsWith("Basic ");
        });
    }

    @Test
    void sessionRequestRelaysAccessTokenAndDropsCookies() throws Exception {
        Login login = login();
        client.get().uri("/report/current").cookie(SESSION, login.session()).accept(MediaType.APPLICATION_JSON).exchange()
                .expectStatus().isOk();
        RecordedRequest relayed = DOWNSTREAM.takeRequest(5, TimeUnit.SECONDS);
        assertThat(relayed).isNotNull();
        assertThat(relayed.getPath()).isEqualTo("/current");
        assertThat(relayed.getHeader(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer " + login.accessToken());
        assertThat(relayed.getHeader(HttpHeaders.COOKIE)).isNull();
    }

    @Test
    void bearerRequestPassesThroughWithoutSessionOrCsrfCookie() throws Exception {
        String token = KEYCLOAK.bearerToken();
        EntityExchangeResult<byte[]> result = client.get().uri("/report/current").headers(h -> h.setBearerAuth(token)).exchange()
                .expectStatus().isOk().expectBody().returnResult();
        assertThat(result.getResponseCookies()).doesNotContainKeys(SESSION, XSRF);
        RecordedRequest relayed = DOWNSTREAM.takeRequest(5, TimeUnit.SECONDS);
        assertThat(relayed).isNotNull();
        assertThat(relayed.getHeader(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer " + token);
    }

    @Test
    void authorizedClientWithItsRefreshTokenLivesInTheSession() {
        Login login = login();
        MapSession session = sessions.findById(login.session()).block(Duration.ofSeconds(5));
        assertThat(session).isNotNull();
        assertThat(session.getAttributeNames()).contains(WebSessionServerOAuth2AuthorizedClientRepository.class.getName() + ".AUTHORIZED_CLIENTS");
    }

    @Test
    void bearerSignedWithAnotherRsaAlgorithmIsRejected() throws Exception {
        String token = KEYCLOAK.bearerTokenSignedWith(JWSAlgorithm.RS512);
        client.get().uri("/report/current").headers(h -> h.setBearerAuth(token)).exchange().expectStatus().isUnauthorized();
        assertThat(DOWNSTREAM.takeRequest(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void bearerPostNeedsNoCsrfToken() throws Exception {
        client.post().uri("/report/items").headers(h -> h.setBearerAuth(KEYCLOAK.bearerToken())).contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"fixture\"}").exchange().expectStatus().isOk();
        RecordedRequest relayed = DOWNSTREAM.takeRequest(5, TimeUnit.SECONDS);
        assertThat(relayed).isNotNull();
        assertThat(relayed.getBody().readUtf8()).isEqualTo("{\"name\":\"fixture\"}");
    }

    @Test
    void agentAudienceBearerIsRelayedUnchanged() throws Exception {
        // ai 经令牌交换取得受众 github-ai-agent 的令牌，按 AgentCard 登记的网关地址调 ai-agent，网关要放行并原样转发。
        String token = KEYCLOAK.bearerToken("github-ai-agent");
        client.get().uri("/report/current").headers(h -> h.setBearerAuth(token)).exchange().expectStatus().isOk();
        RecordedRequest relayed = DOWNSTREAM.takeRequest(5, TimeUnit.SECONDS);
        assertThat(relayed).isNotNull();
        assertThat(relayed.getHeader(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer " + token);
    }

    @Test
    void invalidOrForeignAudienceBearerIs401JsonAndNeverReachesTheService() throws Exception {
        for (String token : List.of("not-a-jwt", KEYCLOAK.bearerToken("github-rag-engine"))) {
            client.get().uri("/report/current").headers(h -> h.setBearerAuth(token)).exchange()
                    .expectStatus().isUnauthorized()
                    .expectHeader().value(HttpHeaders.WWW_AUTHENTICATE, value -> assertThat(value).startsWith("Bearer").contains("invalid_token"))
                    .expectBody().jsonPath("$.success").isEqualTo(false).jsonPath("$.code").isEqualTo("A0220");
        }
        assertThat(DOWNSTREAM.takeRequest(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void unauthenticatedApiAndXhrRequestsAre401Json() throws Exception {
        client.get().uri("/report/current").accept(MediaType.APPLICATION_JSON).exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody().jsonPath("$.success").isEqualTo(false).jsonPath("$.code").isEqualTo("A0220");
        client.get().uri("/report/current").accept(MediaType.TEXT_HTML).header("X-Requested-With", "XMLHttpRequest").exchange()
                .expectStatus().isUnauthorized();
        assertThat(DOWNSTREAM.takeRequest(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void unauthenticatedBrowserNavigationRedirectsToLogin() {
        client.get().uri("/report/page").accept(MediaType.TEXT_HTML).exchange()
                .expectStatus().isFound()
                .expectHeader().location("/oauth2/authorization/keycloak");
    }

    @Test
    void sessionPostWithoutCsrfIsForbiddenJson() throws Exception {
        Login login = login();
        client.post().uri("/report/items").cookie(SESSION, login.session()).contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"fixture\"}").exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.success").isEqualTo(false).jsonPath("$.code").isEqualTo("A0301");
        assertThat(DOWNSTREAM.takeRequest(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void sessionPostWithCsrfHeaderFromTheCookieSucceeds() throws Exception {
        Login login = login();
        String csrf = csrfCookie(login);
        DOWNSTREAM.takeRequest(5, TimeUnit.SECONDS);
        client.post().uri("/report/items").cookie(SESSION, login.session()).cookie(XSRF, csrf).header("X-XSRF-TOKEN", csrf)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"name\":\"fixture\"}").exchange()
                .expectStatus().isOk();
        RecordedRequest relayed = DOWNSTREAM.takeRequest(5, TimeUnit.SECONDS);
        assertThat(relayed).isNotNull();
        assertThat(relayed.getMethod()).isEqualTo("POST");
        assertThat(relayed.getHeader(HttpHeaders.COOKIE)).isNull();
        assertThat(relayed.getHeader("X-XSRF-TOKEN")).isEqualTo(csrf);
    }

    @Test
    void csrfCookieIsReadableByTheSpa() throws Exception {
        Login login = login();
        EntityExchangeResult<byte[]> result = client.get().uri("/report/current").cookie(SESSION, login.session())
                .accept(MediaType.APPLICATION_JSON).exchange().expectStatus().isOk().expectBody().returnResult();
        ResponseCookie csrf = result.getResponseCookies().getFirst(XSRF);
        assertThat(csrf).isNotNull();
        assertThat(csrf.isHttpOnly()).isFalse();
        assertThat(csrf.getPath()).isEqualTo("/");
    }

    @Test
    void swaggerUiSendsTheCsrfHeader() {
        client.get().uri("/webjars/swagger-ui/swagger-initializer.js").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).value(body -> assertThat(body).contains("X-XSRF-TOKEN"));
    }

    @Test
    void logoutRedirectsToKeycloakEndSessionAndEndsTheGatewaySession() throws Exception {
        Login login = login();
        String csrf = csrfCookie(login);
        EntityExchangeResult<byte[]> logout = client.post().uri("/logout").cookie(SESSION, login.session()).cookie(XSRF, csrf)
                .header("X-XSRF-TOKEN", csrf).exchange().expectStatus().isFound().expectBody().returnResult();
        assertEndSession(logout.getResponseHeaders().getLocation());
        ResponseCookie cleared = logout.getResponseCookies().getFirst(SESSION);
        assertThat(cleared).isNotNull();
        assertThat(cleared.getMaxAge()).isZero();
        assertThat(sessions.findById(login.session()).block(Duration.ofSeconds(5))).isNull();
        client.get().uri("/report/current").cookie(SESSION, login.session()).accept(MediaType.APPLICATION_JSON).exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void xhrLogoutReturnsTheEndSessionLocationForTheSpaToNavigate() throws Exception {
        Login login = login();
        String csrf = csrfCookie(login);
        EntityExchangeResult<byte[]> logout = client.post().uri("/logout").cookie(SESSION, login.session()).cookie(XSRF, csrf)
                .header("X-XSRF-TOKEN", csrf).header("X-Requested-With", "XMLHttpRequest").exchange()
                .expectStatus().isEqualTo(HttpStatus.ACCEPTED).expectBody().returnResult();
        assertEndSession(logout.getResponseHeaders().getLocation());
    }

    @Test
    void logoutWithoutCsrfIsForbidden() throws Exception {
        Login login = login();
        client.post().uri("/logout").cookie(SESSION, login.session()).exchange().expectStatus().isForbidden();
        client.get().uri("/report/current").cookie(SESSION, login.session()).accept(MediaType.APPLICATION_JSON).exchange()
                .expectStatus().isOk();
    }

    @Test
    void concurrentRequestsAtAccessTokenExpiryAllSucceed() throws Exception {
        KEYCLOAK.accessTokenSeconds(30);
        Login login = login();
        KEYCLOAK.tokenRequests().clear();
        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(5);
        try {
            List<Future<Integer>> calls = new ArrayList<>();
            for (int index = 0; index < 5; index++) {
                calls.add(executor.submit(() -> {
                    gate.await();
                    return client.get().uri("/report/current").cookie(SESSION, login.session()).accept(MediaType.APPLICATION_JSON)
                            .exchange().returnResult(String.class).getStatus().value();
                }));
            }
            gate.countDown();
            for (Future<Integer> call : calls) {
                assertThat(call.get(30, TimeUnit.SECONDS)).isEqualTo(200);
            }
        } finally {
            executor.shutdownNow();
        }
        for (int index = 0; index < 5; index++) {
            RecordedRequest relayed = DOWNSTREAM.takeRequest(5, TimeUnit.SECONDS);
            assertThat(relayed).isNotNull();
            assertThat(relayed.getHeader(HttpHeaders.AUTHORIZATION)).startsWith("Bearer fixture-access-token-")
                    .isNotEqualTo("Bearer " + login.accessToken());
        }
        assertThat(KEYCLOAK.tokenRequests()).isNotEmpty()
                .allSatisfy(request -> assertThat(request.get("grant_type")).isEqualTo("refresh_token"));
    }

    @Test
    void healthStaysPublic() {
        client.get().uri("/actuator/health").exchange().expectStatus().isOk();
    }

    private void assertEndSession(URI location) {
        assertThat(location).isNotNull();
        assertThat(location.toString()).startsWith(KEYCLOAK.issuer() + "/protocol/openid-connect/logout?");
        MultiValueMap<String, String> query = UriComponentsBuilder.fromUri(location).build().getQueryParams();
        assertThat(query.getFirst("id_token_hint")).isNotBlank();
        assertThat(decode(query.getFirst("post_logout_redirect_uri"))).isEqualTo("http://127.0.0.1:" + port + "/");
    }

    /** 走一遍登录：拿授权跳转里的 state 与 nonce，模拟 Keycloak 回调，返回登录后的会话。 */
    private Login login() {
        EntityExchangeResult<byte[]> start = client.get().uri("/oauth2/authorization/keycloak").exchange()
                .expectStatus().isFound().expectBody().returnResult();
        ResponseCookie preLogin = start.getResponseCookies().getFirst(SESSION);
        assertThat(preLogin).isNotNull();
        MultiValueMap<String, String> query = UriComponentsBuilder.fromUri(start.getResponseHeaders().getLocation()).build().getQueryParams();
        KEYCLOAK.expectNonce(decode(query.getFirst("nonce")));
        EntityExchangeResult<byte[]> callback = client.get()
                .uri("/login/oauth2/code/keycloak?code={code}&state={state}", FakeKeycloak.CODE, decode(query.getFirst("state")))
                .cookie(SESSION, preLogin.getValue()).exchange().expectStatus().isFound().expectBody().returnResult();
        assertThat(callback.getResponseHeaders().getLocation()).hasToString("/");
        ResponseCookie session = callback.getResponseCookies().getFirst(SESSION);
        return new Login(session == null ? preLogin.getValue() : session.getValue(), preLogin.getValue(),
                decode(query.getFirst("code_challenge")), KEYCLOAK.lastAccessToken());
    }

    /** 登录后的第一个转发请求会下发 CSRF Cookie（单页应用从 Cookie 读值，写请求放进 X-XSRF-TOKEN 头）。 */
    private String csrfCookie(Login login) {
        EntityExchangeResult<byte[]> result = client.get().uri("/report/current").cookie(SESSION, login.session())
                .accept(MediaType.APPLICATION_JSON).exchange().expectStatus().isOk().expectBody().returnResult();
        ResponseCookie csrf = result.getResponseCookies().getFirst(XSRF);
        assertThat(csrf).isNotNull();
        return csrf.getValue();
    }

    private static String decode(String value) {
        return value == null ? null : URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String s256(String verifier) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    private static MockWebServer downstream() {
        MockWebServer server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody("{\"ok\":true}");
            }
        });
        try {
            server.start(InetAddress.getLoopbackAddress(), 0);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
        return server;
    }

    /**
     * 登录结果。
     *
     * @param session           登录后的会话 Cookie 值
     * @param preLoginSession   登录前（保存授权请求）的会话 Cookie 值
     * @param challenge         授权请求里的 code_challenge
     * @param accessToken       登录时签发的访问令牌
     */
    private record Login(String session, String preLoginSession, String challenge, String accessToken) {
    }
}

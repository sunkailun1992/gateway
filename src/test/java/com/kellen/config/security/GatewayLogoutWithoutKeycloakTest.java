package com.kellen.config.security;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.server.WebFilterExchange;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 注销时 Keycloak 不可达（网关重启后还没发现过 Keycloak）：本地会话照常作废（注销处理器在它之前已执行），
 * 跳转退回本地首页，不把注销报成 503；Keycloak 的 SSO 会话这时结束不了，恢复后用户再注销一次即可。
 */
class GatewayLogoutWithoutKeycloakTest {

    @Test
    void logoutFallsBackToTheLocalPageWhenKeycloakIsUnreachable() {
        ReactiveClientRegistrationRepository unreachable = id -> Mono.error(
                new ClientRegistrationUnavailableException("Keycloak 发现文档暂不可达", new IOException("connection refused")));
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/logout"));
        GatewaySecurityConfig.logoutSuccessHandler(unreachable)
                .onLogoutSuccess(new WebFilterExchange(exchange, ignored -> Mono.empty()), oidcLogin())
                .block(Duration.ofSeconds(5));
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(exchange.getResponse().getHeaders().getLocation()).isEqualTo(URI.create("/"));
    }

    /**
     * 单页应用的 XHR 注销同样退回本地首页，按单页应用的约定回 202 加 {@code Location}，由前端自己导航。
     */
    @Test
    void xhrLogoutFallsBackToTheLocalPageForTheSpaToNavigate() {
        ReactiveClientRegistrationRepository unreachable = id -> Mono.error(
                new ClientRegistrationUnavailableException("Keycloak 发现文档暂不可达", new IOException("connection refused")));
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/logout").header("X-Requested-With", "XMLHttpRequest"));
        GatewaySecurityConfig.logoutSuccessHandler(unreachable)
                .onLogoutSuccess(new WebFilterExchange(exchange, ignored -> Mono.empty()), oidcLogin())
                .block(Duration.ofSeconds(5));
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(exchange.getResponse().getHeaders().getLocation()).isEqualTo(URI.create("/"));
    }

    private static OAuth2AuthenticationToken oidcLogin() {
        Instant now = Instant.now();
        OidcIdToken idToken = OidcIdToken.withTokenValue("fixture-id-token").subject("kc-user-1").issuer("http://127.0.0.1:1/realms/github-dev")
                .issuedAt(now).expiresAt(now.plusSeconds(300)).build();
        return new OAuth2AuthenticationToken(new DefaultOidcUser(List.of(), idToken), List.of(), GatewaySecurityConfig.REGISTRATION_ID);
    }
}

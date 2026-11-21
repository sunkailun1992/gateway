package com.kellen.config.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.URI;

/**
 * TokenRelay 取不到可用访问令牌时的出口（缺省会变成 500 并打印堆栈，之后的请求再被转成跳 Keycloak 的 302）。
 *
 * <ul>
 *     <li>刷新令牌被拒或需要重新授权（Keycloak 回 invalid_grant：刷新令牌空闲或最长期限到期、用户被停用或删除）：作废会话；
 *     单页应用的 XHR / API 请求返回 401 JSON（A0230 登录已过期），由前端整页跳登录，浏览器整页导航直接回登录入口。
 *     BFF 在会话令牌失效时回 401、由前端发起登录，见 IETF《OAuth 2.0 for Browser-Based Apps》的 BFF 一节。</li>
 *     <li>Keycloak 令牌端点不可用（连接失败、超时、连接被断开或响应不是令牌 JSON）：返回 503 JSON（C0001），会话与刷新令牌保留，
 *     Keycloak 恢复后照常续期；只记一行警告。</li>
 * </ul>
 *
 * <p>排在全部网关过滤器之前，包住 TokenRelay。网关过滤链里只有 TokenRelay 续期时用 WebClient，转发到下游服务的连接错误不经这里。</p>
 *
 * @author 孙凯伦
 */
public class TokenRelayFailureFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(TokenRelayFailureFilter.class);

    /**
     * Spring Security 读不到有效令牌响应时用的错误码（{@code AbstractWebClientReactiveOAuth2AccessTokenResponseClient}）。
     */
    private static final String INVALID_TOKEN_RESPONSE = "invalid_token_response";

    /**
     * 由网关安全配置注册。
     */
    public TokenRelayFailureFilter() {
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return chain.filter(exchange)
                .onErrorResume(WebClientRequestException.class, failure -> unreachable(exchange, failure))
                .onErrorResume(OAuth2AuthorizationException.class, failure -> sessionExpired(exchange, failure));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    private Mono<Void> unreachable(ServerWebExchange exchange, WebClientRequestException failure) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(failure);
        }
        log.warn("续期访问令牌时连不上 Keycloak 令牌端点：{}", rootCause(failure));
        return GatewayErrorResponses.write(exchange, HttpStatus.SERVICE_UNAVAILABLE, "C0001", "调用第三方服务出错");
    }

    private Mono<Void> sessionExpired(ServerWebExchange exchange, OAuth2AuthorizationException failure) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(failure);
        }
        if (tokenEndpointUnavailable(failure)) {
            log.warn("续期访问令牌时 Keycloak 令牌端点没有给出有效响应：{}", rootCause(failure));
            return GatewayErrorResponses.write(exchange, HttpStatus.SERVICE_UNAVAILABLE, "C0001", "调用第三方服务出错");
        }
        log.info("会话里的令牌已不能续期（{}），会话作废", failure.getError().getErrorCode());
        return exchange.getSession().flatMap(WebSession::invalidate)
                .then(GatewaySecurityConfig.browserNavigation(exchange))
                .flatMap(navigation -> navigation ? redirectToLogin(exchange)
                        : GatewayErrorResponses.write(exchange, HttpStatus.UNAUTHORIZED, "A0230", "用户登录已过期"));
    }

    private static Mono<Void> redirectToLogin(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FOUND);
        response.getHeaders().setLocation(URI.create(GatewaySecurityConfig.LOGIN_ENTRY));
        return response.setComplete();
    }

    /**
     * 令牌端点不可用而不是令牌被拒：Spring Security 把读不到有效令牌响应（连接被断开、响应不是令牌 JSON）记为
     * {@value #INVALID_TOKEN_RESPONSE}，此时会话里的已授权客户端不会被删除，Keycloak 恢复后照常续期。
     */
    private static boolean tokenEndpointUnavailable(OAuth2AuthorizationException failure) {
        if (INVALID_TOKEN_RESPONSE.equals(failure.getError().getErrorCode())) {
            return true;
        }
        for (Throwable cause = failure.getCause(); cause != null && cause != cause.getCause(); cause = cause.getCause()) {
            if (cause instanceof IOException || cause instanceof WebClientRequestException) {
                return true;
            }
        }
        return false;
    }

    private static String rootCause(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + (root.getMessage() == null ? "" : "：" + root.getMessage());
    }
}

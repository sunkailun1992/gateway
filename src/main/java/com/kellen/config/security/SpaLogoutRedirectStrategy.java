package com.kellen.config.security;

import org.springframework.http.HttpStatus;
import org.springframework.security.web.server.DefaultServerRedirectStrategy;
import org.springframework.security.web.server.ServerRedirectStrategy;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;

/**
 * 注销后的跳转：XHR 请求（{@code X-Requested-With: XMLHttpRequest}）返回 202 加 {@code Location}，由单页应用自己导航到
 * Keycloak 的注销端点（fetch 跟随跨源跳转会被 CORS 拦下，Keycloak 的 SSO 会话就结束不了）；其余请求照常 302。
 *
 * @author 孙凯伦
 */
final class SpaLogoutRedirectStrategy implements ServerRedirectStrategy {

    private final ServerRedirectStrategy redirect = new DefaultServerRedirectStrategy();

    @Override
    public Mono<Void> sendRedirect(ServerWebExchange exchange, URI location) {
        if ("XMLHttpRequest".equalsIgnoreCase(exchange.getRequest().getHeaders().getFirst("X-Requested-With"))) {
            return Mono.fromRunnable(() -> {
                exchange.getResponse().setStatusCode(HttpStatus.ACCEPTED);
                exchange.getResponse().getHeaders().setLocation(location);
            });
        }
        return redirect.sendRedirect(exchange, location);
    }
}

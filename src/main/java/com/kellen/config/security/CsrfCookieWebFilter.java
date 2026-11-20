package com.kellen.config.security;

import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 订阅延迟生成的 CSRF 令牌，让 {@code XSRF-TOKEN} Cookie 随响应下发，单页应用才能读到（Spring Security 文档
 * 「Single-Page Applications」的 WebFlux 写法）。
 *
 * @author 孙凯伦
 */
final class CsrfCookieWebFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        Mono<CsrfToken> token = exchange.getAttributeOrDefault(CsrfToken.class.getName(), Mono.empty());
        return token.then(chain.filter(exchange));
    }
}

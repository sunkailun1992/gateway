package com.kellen.config.security;

import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.security.web.server.csrf.ServerCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.server.csrf.ServerCsrfTokenRequestHandler;
import org.springframework.security.web.server.csrf.XorServerCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 单页应用的 CSRF 令牌处理（Spring Security 文档「Single-Page Applications」的 WebFlux 写法）：渲染进响应体时总用 XOR 掩码
 * （防 BREACH）；请求头里的值是单页应用从 Cookie 读到的原值，按原值比对；表单参数仍按 XOR 掩码比对。
 *
 * @author 孙凯伦
 */
final class SpaServerCsrfTokenRequestHandler implements ServerCsrfTokenRequestHandler {

    private final ServerCsrfTokenRequestHandler plain = new ServerCsrfTokenRequestAttributeHandler();

    private final ServerCsrfTokenRequestHandler xor = new XorServerCsrfTokenRequestAttributeHandler();

    @Override
    public void handle(ServerWebExchange exchange, Mono<CsrfToken> csrfToken) {
        xor.handle(exchange, csrfToken);
    }

    @Override
    public Mono<String> resolveCsrfTokenValue(ServerWebExchange exchange, CsrfToken csrfToken) {
        String header = exchange.getRequest().getHeaders().getFirst(csrfToken.getHeaderName());
        return (StringUtils.hasText(header) ? plain : xor).resolveCsrfTokenValue(exchange, csrfToken);
    }
}

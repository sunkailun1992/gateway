package com.kellen.config.security;

import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.security.web.server.csrf.ServerCsrfTokenRequestHandler;
import org.springframework.security.web.server.csrf.XorServerCsrfTokenRequestAttributeHandler;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 单页应用的 CSRF 令牌处理（Spring Security 文档「Single-Page Applications」的 WebFlux 写法）：渲染进请求属性时总用 XOR 掩码
 * （防 BREACH）；校验只认请求头 {@code X-XSRF-TOKEN}，值是单页应用从 {@code XSRF-TOKEN} Cookie 读到的原值，按原值比对。
 *
 * <p>不收表单参数 {@code _csrf}：admin-web 只用请求头，网关也没有表单页面；为了找参数去读写请求的表单正文，会把正文提前消费掉，
 * 转发到下游时正文就没了（缺省实现正是先读表单再看请求头）。做法同 OWASP CSRF 防护清单对单页应用的「自定义请求头」方案。</p>
 *
 * @author 孙凯伦
 */
final class SpaServerCsrfTokenRequestHandler implements ServerCsrfTokenRequestHandler {

    private final ServerCsrfTokenRequestHandler xor = new XorServerCsrfTokenRequestAttributeHandler();

    @Override
    public void handle(ServerWebExchange exchange, Mono<CsrfToken> csrfToken) {
        xor.handle(exchange, csrfToken);
    }

    @Override
    public Mono<String> resolveCsrfTokenValue(ServerWebExchange exchange, CsrfToken csrfToken) {
        return Mono.justOrEmpty(exchange.getRequest().getHeaders().getFirst(csrfToken.getHeaderName()));
    }
}

package com.kellen.config.security;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.security.web.server.csrf.CsrfException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 拒绝访问：403（A0301），响应体是统一 JSON；缺少或无效的 CSRF 令牌单独说明，便于前端提示刷新后重试。
 *
 * @author 孙凯伦
 */
public class ApiAccessDeniedHandler implements ServerAccessDeniedHandler {

    /**
     * 构造处理器。
     */
    public ApiAccessDeniedHandler() {
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, AccessDeniedException denied) {
        String message = denied instanceof CsrfException ? "缺少或无效的 CSRF 令牌" : "访问未授权";
        return GatewayErrorResponses.write(exchange, HttpStatus.FORBIDDEN, "A0301", message);
    }
}

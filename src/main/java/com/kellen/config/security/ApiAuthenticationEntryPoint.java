package com.kellen.config.security;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.BearerTokenError;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 未认证的 API 请求：401（A0220），{@code WWW-Authenticate} 按 RFC 6750 写 Bearer 质询（令牌无效时带 error 与说明），
 * 响应体是统一 JSON。不回显令牌。
 *
 * @author 孙凯伦
 */
public class ApiAuthenticationEntryPoint implements ServerAuthenticationEntryPoint {

    /**
     * 构造入口。
     */
    public ApiAuthenticationEntryPoint() {
    }

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException exception) {
        return Mono.defer(() -> {
            exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, challenge(exception));
            return GatewayErrorResponses.write(exchange, HttpStatus.UNAUTHORIZED, "A0220", "用户身份校验失败");
        });
    }

    private static String challenge(AuthenticationException exception) {
        if (!(exception instanceof OAuth2AuthenticationException oauth) || !(oauth.getError() instanceof BearerTokenError error)) {
            return "Bearer";
        }
        StringBuilder value = new StringBuilder("Bearer error=\"").append(error.getErrorCode()).append('"');
        if (StringUtils.hasText(error.getDescription())) {
            value.append(", error_description=\"").append(error.getDescription().replace("\"", "'")).append('"');
        }
        if (StringUtils.hasText(error.getUri())) {
            value.append(", error_uri=\"").append(error.getUri()).append('"');
        }
        return value.toString();
    }
}

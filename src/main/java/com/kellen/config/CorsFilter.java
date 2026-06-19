package com.kellen.config;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.reactive.CorsUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 网关跨域预检过滤器。
 *
 * <p>在路由转发前处理浏览器 CORS 预检请求，保证前端能携带认证头访问后端；
 * 该过滤器只处理跨域响应头，不承担业务鉴权或字段级授权。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorsFilter implements WebFilter {

    /**
     * 处理跨域请求和预检请求。
     *
     * @param exchange 当前网关请求上下文
     * @param chain    后续过滤器链
     * @return 响应完成信号
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        if (CorsUtils.isCorsRequest(request)) {
            ServerHttpResponse response = exchange.getResponse();
            response.beforeCommit(() -> {
                applyCorsHeaders(request, response);
                return Mono.empty();
            });
            if (request.getMethod() == HttpMethod.OPTIONS) {
                response.setStatusCode(HttpStatus.OK);
                applyCorsHeaders(request, response);
                return response.setComplete();
            }
        }
        return chain.filter(exchange);
    }

    /**
     * 写入跨域响应头。
     *
     * @param request  当前请求
     * @param response 当前响应
     */
    private void applyCorsHeaders(ServerHttpRequest request, ServerHttpResponse response) {
        HttpHeaders headers = response.getHeaders();
        String origin = request.getHeaders().getOrigin();
        headers.set("Access-Control-Allow-Origin", origin == null ? "*" : origin);
        headers.set("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,PATCH,OPTIONS");
        headers.set("Access-Control-Max-Age", "18000");
        headers.set("Access-Control-Allow-Headers", "*");
        headers.set("Access-Control-Allow-Credentials", "true");
        headers.set("Access-Control-Expose-Headers", "Authorization");
    }
}

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
 * Handles CORS preflight requests before route forwarding.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorsFilter implements WebFilter {

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

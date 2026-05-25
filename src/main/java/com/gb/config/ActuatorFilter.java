package com.gb.config;

import cn.hutool.core.collection.CollectionUtil;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ActuatorFilter implements WebFilter, Ordered {
    private static final Pattern actuatorReqPattern = Pattern.compile("/actuator$|/actuator/", Pattern.CASE_INSENSITIVE);


    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        /**
         * 请求地址
         */
//        String reqPath = exchange.getRequest().getURI().getPath();
        String requestURI = exchange.getRequest().getURI().getPath();
        Matcher matcher = actuatorReqPattern.matcher(requestURI);
        boolean match = matcher.find();
        if (match) {
            HttpHeaders headers = exchange.getRequest().getHeaders();
            List<String> actuatorReqTokenList = headers.get("actuatorReq");
            if (CollectionUtil.isEmpty(actuatorReqTokenList)) {
                return unAuth(exchange);
            }
            String actuatorReq = actuatorReqTokenList.get(0);
            if (!"e70d96bc080044c5840b85ab986832ae".equals(actuatorReq)) {
                return unAuth(exchange);
            }
        }
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return 1;
    }


    private Mono unAuth(ServerWebExchange exchange) {
        //不允许访问，禁止访问
        ServerHttpResponse response = exchange.getResponse();
        //这个状态码是401
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }

}

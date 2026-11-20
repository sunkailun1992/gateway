package com.kellen.config.security;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 网关安全错误的统一 JSON：字段与各服务的统一响应（ApiResponse）一致，便于 admin-web 用同一套逻辑处理。不回显令牌或异常细节。
 *
 * @author 孙凯伦
 */
final class GatewayErrorResponses {

    private GatewayErrorResponses() {
    }

    /**
     * 写错误响应。
     *
     * @param exchange 当前请求
     * @param status   HTTP 状态
     * @param code     统一错误码（与 utils ReturnCode 一致，如 A0220、A0301）
     * @param message  稳定的错误提示（不含请求数据）
     * @return 写完成
     */
    static Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String code, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"success\":false,\"code\":\"" + code + "\",\"data\":null,\"errorMessage\":\"" + message + "\"}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }
}

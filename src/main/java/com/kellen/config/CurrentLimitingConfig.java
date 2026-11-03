package com.kellen.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

/**
 * 网关限流键配置。
 *
 * <p>默认按客户端 IP 生成限流 key，用于 Gateway RedisRateLimiter 等过滤器；
 * 无法解析远端地址时回退到固定 key，避免限流逻辑因空地址异常失败开放。</p>
 */
@Configuration
public class CurrentLimitingConfig {

    private static final String UNKNOWN_CLIENT = "unknown";

    /**
     * 基于客户端 IP 的限流 key 解析器。
     *
     * @return Gateway 限流 key 解析器
     */
    @Bean
    protected KeyResolver ipKeyResolver() {
        return exchange -> {
            InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
            if (remoteAddress == null || remoteAddress.getAddress() == null) {
                return Mono.just(UNKNOWN_CLIENT);
            }
            return Mono.just(remoteAddress.getAddress().getHostAddress());
        };
    }
}

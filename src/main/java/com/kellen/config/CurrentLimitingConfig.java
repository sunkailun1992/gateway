package com.kellen.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

/**
 * Gateway rate-limit key configuration.
 */
@Configuration
public class CurrentLimitingConfig {

    private static final String UNKNOWN_CLIENT = "unknown";

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

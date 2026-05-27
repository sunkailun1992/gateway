package com.kellen.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * 限流
 * @author sunkailun
 * @DateTime 2020/12/29  上午9:22
 * @email 376253703@qq.com
 * @phone 13777579028
 * @explain
 */
@Slf4j
@Configuration
public class CurrentLimitingConfig {
    @Bean
    protected KeyResolver ipKeyResolver() {
        return exchange -> Mono.just(exchange.getRequest().getRemoteAddress().getHostName());
    }
}

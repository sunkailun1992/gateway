package com.kellen.config;

import com.kellen.traffic.CanaryGrant;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** 在路由匹配前移除客户端提供的流量治理头。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TrafficGovernanceHeaderFilter implements WebFilter {

    static final String CANARY_GRANTS_ATTRIBUTE =
            TrafficGovernanceHeaderFilter.class.getName() + ".canaryGrants";

    private static final List<String> GRAY_HEADERS = List.of(
            "X-Canary-Tag", "X-Canary-Weight", "canary.tag", "traffic.weight",
            "dubbo-tag", "dubbo.tag", "dubbo.force.tag", CanaryGrant.HEADER);

    private static final List<String> BUSINESS_HEADERS = List.of(
            HttpHeaders.AUTHORIZATION, HttpHeaders.COOKIE, "X-Tenant-Id", "tenant-id", "tenantId",
            "traceparent", "tracestate", "baggage", "X-Request-Id", "X-Trace-Id");

    private final GatewayTrafficGovernanceProperties properties;

    public TrafficGovernanceHeaderFilter(GatewayTrafficGovernanceProperties properties) {
        this.properties = properties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        GatewayTrafficGovernanceProperties.Request config = properties.getRequest();
        ServerHttpRequest request = exchange.getRequest();
        List<String> grants = request.getHeaders().get(CanaryGrant.HEADER);
        if (grants != null) {
            exchange.getAttributes().put(CANARY_GRANTS_ATTRIBUTE, List.copyOf(grants));
        }

        ServerHttpRequest mutatedRequest = request.mutate().headers(headers -> {
            GRAY_HEADERS.forEach(headers::remove);
            headers.remove("X-Release-Version");
            headers.remove("X-Traffic-Lane");
            headers.remove("release.version");
            headers.remove("traffic.lane");
            removeConfiguredHeader(headers, config.getReleaseVersionHeader());
            removeConfiguredHeader(headers, config.getLaneHeader());
            removeConfiguredHeader(headers, config.getCanaryTagHeader());
            removeConfiguredHeader(headers, config.getCanaryWeightHeader());
            if (grants == null && properties.isEnabled()) {
                setDefault(headers, config.getReleaseVersionHeader(), config.getDefaultReleaseVersion(), config);
                setDefault(headers, config.getLaneHeader(), config.getDefaultLane(), config);
            }
        }).build();
        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    private void removeConfiguredHeader(HttpHeaders headers, String name) {
        if (StringUtils.hasText(name) && BUSINESS_HEADERS.stream().noneMatch(name::equalsIgnoreCase)) {
            headers.remove(name);
        }
    }

    private void setDefault(HttpHeaders headers, String name, String value,
                            GatewayTrafficGovernanceProperties.Request config) {
        if (!StringUtils.hasText(name) || !StringUtils.hasText(value)
                || BUSINESS_HEADERS.stream().anyMatch(name::equalsIgnoreCase)
                || GRAY_HEADERS.stream().anyMatch(name::equalsIgnoreCase)) {
            return;
        }
        String normalized = value.trim();
        try {
            if (Pattern.matches(config.getAllowedValuePattern(), normalized)) {
                headers.set(name, normalized);
            }
        } catch (PatternSyntaxException ignored) {
            // 配置格式错误时忽略默认值。
        }
    }
}

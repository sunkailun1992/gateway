package com.kellen.config;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 网关流量治理请求头标准化过滤器。
 *
 * <p>公网入口只信任发布版本、泳道和灰度 tag 的合法短值；权重默认不接受客户端头，
 * 比例发布由 Nacos/Dubbo 治理规则控制。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TrafficGovernanceHeaderFilter implements WebFilter {

    /**
     * Dubbo Triple/HTTP tag 头。
     */
    private static final String DUBBO_TAG_HEADER = "dubbo-tag";

    /**
     * 流量治理配置。
     */
    private final GatewayTrafficGovernanceProperties properties;

    public TrafficGovernanceHeaderFilter(GatewayTrafficGovernanceProperties properties) {
        this.properties = properties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (properties == null || !properties.isEnabled()) {
            return chain.filter(exchange);
        }
        GatewayTrafficGovernanceProperties.Request config = properties.getRequest();
        ServerHttpRequest request = exchange.getRequest();
        String releaseVersion = sanitize(firstNotBlank(
                request.getHeaders().getFirst(config.getReleaseVersionHeader()),
                config.getDefaultReleaseVersion()), config);
        String lane = sanitize(firstNotBlank(
                request.getHeaders().getFirst(config.getLaneHeader()),
                config.getDefaultLane()), config);
        String resolvedCanaryTag = sanitize(request.getHeaders().getFirst(config.getCanaryTagHeader()), config);
        if (!StringUtils.hasText(resolvedCanaryTag) && config.isTagFallbackToReleaseVersion()) {
            resolvedCanaryTag = releaseVersion;
        }
        String resolvedCanaryWeight = null;
        if (config.isAllowClientWeightHeader()) {
            resolvedCanaryWeight = sanitizeWeight(request.getHeaders().getFirst(config.getCanaryWeightHeader()));
        }
        String canaryTag = resolvedCanaryTag;
        String canaryWeight = resolvedCanaryWeight;

        ServerHttpRequest mutatedRequest = request.mutate().headers(headers -> {
            setOrRemove(headers, config.getReleaseVersionHeader(), releaseVersion);
            setOrRemove(headers, config.getLaneHeader(), lane);
            setOrRemove(headers, config.getCanaryTagHeader(), canaryTag);
            setOrRemove(headers, DUBBO_TAG_HEADER, canaryTag);
            setOrRemove(headers, config.getCanaryWeightHeader(), canaryWeight);
        }).build();
        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    /**
     * 写入或删除请求头。
     *
     * @param headers 请求头集合
     * @param name    请求头名
     * @param value   请求头值
     */
    private void setOrRemove(org.springframework.http.HttpHeaders headers, String name, String value) {
        if (!StringUtils.hasText(name)) {
            return;
        }
        headers.remove(name);
        if (StringUtils.hasText(value)) {
            headers.set(name, value);
        }
    }

    /**
     * 过滤治理字段。
     *
     * @param value  原始值
     * @param config 请求侧配置
     * @return 合法值，非法时返回 null
     */
    private String sanitize(String value, GatewayTrafficGovernanceProperties.Request config) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return null;
        }
        try {
            return Pattern.matches(config.getAllowedValuePattern(), normalized) ? normalized : null;
        } catch (PatternSyntaxException ex) {
            return null;
        }
    }

    /**
     * 过滤权重字段。
     *
     * @param value 原始权重
     * @return 合法权重，非法时返回 null
     */
    private String sanitizeWeight(String value) {
        String normalized = trimToNull(value);
        if (normalized == null || !normalized.chars().allMatch(Character::isDigit)) {
            return null;
        }
        int weight = Integer.parseInt(normalized);
        return weight >= 0 && weight <= 100 ? normalized : null;
    }

    /**
     * 返回第一个非空字符串。
     *
     * @param first  第一个候选值
     * @param second 第二个候选值
     * @return 第一个非空字符串
     */
    private String firstNotBlank(String first, String second) {
        return StringUtils.hasText(first) ? first : second;
    }

    /**
     * 去掉空白并把空串转为 null。
     *
     * @param value 原始值
     * @return 非空字符串或 null
     */
    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }
}

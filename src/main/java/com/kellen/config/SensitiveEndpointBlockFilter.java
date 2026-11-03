package com.kellen.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 网关公网暴露面收敛过滤器。
 *
 * <p>公网流量都从网关进入：网关自身的 Actuator、经 {@code /{service}/**} 路由转发到后端服务的 Actuator，
 * 以及 Swagger UI / OpenAPI 文档，原先是否对外取决于 Nacos 的 management/springdoc 配置和外层 nginx。
 * 为防止远程配置误将 shutdown、env 等管理入口暴露，这里在路由和端点分发之前
 * 按路径直接返回 404，让这些入口不再依赖远程配置是否收紧。</p>
 *
 * <p>只按路径判定，不读取 token、身份或权限头，属于暴露面收敛而不是鉴权；认证授权仍由后端服务负责。
 * 网关自身的 {@code /actuator/health}（含 liveness/readiness）保留给探活，其它运维端点需要时走内网直连实例端口。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class SensitiveEndpointBlockFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(SensitiveEndpointBlockFilter.class);

    private static final String ACTUATOR = "actuator";

    /**
     * 唯一对外保留的 Actuator 路径：只认网关自身根路径下的探活，不放行后端服务的同名路径。
     */
    private static final Set<List<String>> PUBLIC_HEALTH_PATHS = Set.of(
            List.of(ACTUATOR, "health"),
            List.of(ACTUATOR, "health", "liveness"),
            List.of(ACTUATOR, "health", "readiness"));

    private static final int MAX_LOGGED_LENGTH = 200;

    private final GatewayExposureProperties properties;

    private final boolean prodProfile;

    public SensitiveEndpointBlockFilter(GatewayExposureProperties properties, Environment environment) {
        this.properties = properties;
        this.prodProfile = environment.matchesProfiles("prod");
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        List<String> segments = normalizedSegments(exchange.getRequest().getPath().pathWithinApplication());
        String reason = blockReason(segments);
        if (reason == null) {
            return chain.filter(exchange);
        }
        logBlocked(exchange.getRequest(), reason);
        exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
        return exchange.getResponse().setComplete();
    }

    private String blockReason(List<String> segments) {
        if (segments.contains(ACTUATOR) && !PUBLIC_HEALTH_PATHS.contains(segments)) {
            return "actuator";
        }
        if (!apiDocsEnabled() && segments.stream().anyMatch(SensitiveEndpointBlockFilter::isApiDocsSegment)) {
            return "api-docs";
        }
        return null;
    }

    /**
     * 文档开关未配置时按 profile 取默认值；开关可随 Nacos 刷新，所以每次请求重新读取。
     */
    private boolean apiDocsEnabled() {
        Boolean configured = properties.getApiDocsEnabled();
        return configured != null ? configured : !prodProfile;
    }

    /**
     * 覆盖 Springdoc 的 {@code /v3/api-docs}（含 {@code .yaml}、分组和 swagger-config）、Swagger UI 页面及其 webjars 资源。
     */
    private static boolean isApiDocsSegment(String segment) {
        return segment.equals("api-docs") || segment.startsWith("api-docs.")
                || segment.equals("swagger-ui") || segment.startsWith("swagger-ui.")
                || segment.equals("webjars");
    }

    /**
     * 按路由匹配同样的语义取路径段：使用已解码、去掉 {@code ;} 路径参数的段值，忽略空段并统一小写，
     * 避免同一端点因大小写、多余斜杠或路径参数等写法不同而漏判。
     */
    private static List<String> normalizedSegments(PathContainer path) {
        List<String> segments = new ArrayList<>();
        for (PathContainer.Element element : path.elements()) {
            if (element instanceof PathContainer.PathSegment segment) {
                for (String part : segment.valueToMatch().split("[/\\\\;]")) {
                    if (!part.isEmpty()) {
                        segments.add(part.toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        return segments;
    }

    private void logBlocked(ServerHttpRequest request, String reason) {
        log.warn("[SECURITY] 拦截敏感端点 reason={} method={} path={} remote={} forwardedFor={} requestId={}",
                reason, request.getMethod(), sanitize(request.getPath().value()), remoteAddress(request),
                sanitize(request.getHeaders().getFirst("X-Forwarded-For")), request.getId());
    }

    private static String remoteAddress(ServerHttpRequest request) {
        InetSocketAddress address = request.getRemoteAddress();
        return address == null || address.getAddress() == null ? "-" : address.getAddress().getHostAddress();
    }

    /**
     * 日志只保留截断后的可见字符，防止路径或代理头里的控制字符伪造日志行。
     */
    private static String sanitize(String value) {
        if (value == null || value.isEmpty()) {
            return "-";
        }
        StringBuilder result = new StringBuilder(Math.min(value.length(), MAX_LOGGED_LENGTH));
        for (int i = 0; i < value.length() && result.length() < MAX_LOGGED_LENGTH; i++) {
            char c = value.charAt(i);
            result.append(Character.isISOControl(c) ? '_' : c);
        }
        return result.toString();
    }
}

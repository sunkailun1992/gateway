package com.kellen.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
 * <p>公网只按路径收敛暴露面，不读取身份或权限头；独立管理监听端口使用运维令牌，业务认证授权仍由后端服务负责。
 * 网关自身的 {@code /actuator/health}（含 liveness/readiness）保留给探活，其它运维采集只走独立管理端口并验证运维令牌。</p>
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
    private final Environment environment;

    public SensitiveEndpointBlockFilter(GatewayExposureProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
        this.prodProfile = environment.matchesProfiles("prod");
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        List<String> segments = normalizedSegments(exchange.getRequest().getPath().pathWithinApplication());
        if (isManagementPort(exchange.getRequest())) {
            return managementRequest(exchange, chain);
        }
        String reason = blockReason(segments);
        // 自定义 Actuator base-path 同样不能在公网主端口暴露。
        String managementBase = environment.getProperty("management.endpoints.web.base-path", "/actuator");
        List<String> baseSegments = normalizedSegments(PathContainer.parsePath(managementBase));
        if (!"/actuator".equals(managementBase) && (baseSegments.isEmpty()
                || containsSequence(segments, baseSegments))) {
            reason = "actuator";
        }
        if (reason == null) {
            return chain.filter(exchange);
        }
        logBlocked(exchange.getRequest(), reason);
        exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
        return exchange.getResponse().setComplete();
    }

    /** 只使用服务器实际监听端口判定管理流量，转发头和客户端 Host 均不能获得管理权限。 */
    private boolean isManagementPort(ServerHttpRequest request) {
        Integer managementPort = environment.getProperty("local.management.port", Integer.class);
        if (ManagementPortType.get(environment) != ManagementPortType.DIFFERENT || managementPort == null) {
            return false;
        }
        try {
            Object nativeRequest = ServerHttpRequestDecorator.getNativeRequest(request);
            if (!(nativeRequest instanceof reactor.netty.http.server.HttpServerRequest nettyRequest)) {
                return false;
            }
            AtomicReference<SocketAddress> local = new AtomicReference<>();
            // Spring/Netty 的逻辑 localAddress 可能已受 Forwarded by= 改写，只认原始 Channel 地址。
            nettyRequest.withConnection(connection -> local.set(connection.channel().localAddress()));
            return local.get() instanceof InetSocketAddress address && address.getPort() == managementPort;
        } catch (IllegalArgumentException unsupportedRequest) {
            return false;
        }
    }

    /** 独立管理服务器只允许探活和受令牌保护的 GET Prometheus；不受远程 exposure=* 扩大。 */
    private Mono<Void> managementRequest(ServerWebExchange exchange, WebFilterChain chain) {
        String base = environment.getProperty("management.endpoints.web.base-path", "/actuator");
        if ("/".equals(base)) base = "";
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        String health = base + "/" + environment.getProperty("management.endpoints.web.path-mapping.health", "health");
        String prometheus = base + "/" + environment.getProperty("management.endpoints.web.path-mapping.prometheus", "prometheus");
        boolean read = exchange.getRequest().getMethod() == org.springframework.http.HttpMethod.GET;
        if (read && (path.equals(health) || path.equals(health + "/liveness") || path.equals(health + "/readiness"))) {
            return chain.filter(exchange);
        }
        if (!read || !path.equals(prometheus)) {
            exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
            return exchange.getResponse().setComplete();
        }
        String token;
        try {
            token = environment.getProperty("security.actuator.token");
        } catch (IllegalArgumentException unresolved) {
            token = null;
        }
        String provided = exchange.getRequest().getHeaders().getFirst("actuatorReq");
        if (token == null || token.isBlank() || provided == null || !MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8))) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }

    private static boolean containsSequence(List<String> path, List<String> sequence) {
        for (int i = 0; i <= path.size() - sequence.size(); i++) {
            if (path.subList(i, i + sequence.size()).equals(sequence)) return true;
        }
        return false;
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

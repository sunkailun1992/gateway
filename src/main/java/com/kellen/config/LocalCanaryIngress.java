package com.kellen.config;

import com.kellen.traffic.CanaryGrant;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.adapter.ForwardedHeaderTransformer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Instant;
import java.util.List;

/** dev 的回环网关按共享本机标签选择灰度；仅签路由授权，业务认证仍由后端处理。 */
@Component
@Profile("dev & !test & !prod")
final class LocalCanaryIngress {

    private final GatewayTrafficGovernanceProperties properties;
    private final Environment environment;
    private final boolean hasForwardedHeaderTransformer;

    LocalCanaryIngress(GatewayTrafficGovernanceProperties properties, Environment environment,
                       ObjectProvider<ForwardedHeaderTransformer> forwardedHeaderTransformers) {
        this.properties = properties;
        this.environment = environment;
        this.hasForwardedHeaderTransformer = forwardedHeaderTransformers.stream().findAny().isPresent();
    }

    String grant(ServerWebExchange exchange, String service) {
        // 地址必须来自连接本身，不能让转发头转换器先把远端连接改写成回环。
        if (hasForwardedHeaderTransformer || !"none".equalsIgnoreCase(
                environment.getProperty("server.forward-headers-strategy", "none"))) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        }
        var request = exchange.getRequest();
        if (!loopback(request.getLocalAddress()) || !loopback(request.getRemoteAddress())
                || request.getHeaders().headerNames().stream().anyMatch(name ->
                "Forwarded".equalsIgnoreCase(name) || name.regionMatches(true, 0, "X-Forwarded-", 0, 12))
                || !localAuthority(request.getHeaders().get(HttpHeaders.HOST))
                || !localOrigin(request.getHeaders().get(HttpHeaders.ORIGIN))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        var config = properties.getCanary();
        String tag = environment.getProperty("local-canary.tag", "kellen-local");
        var routes = config.getRoutes();
        var tags = routes == null ? null : routes.get(service);
        String destination = tags == null ? null : tags.get(tag);
        if (!localHost(environment.getProperty("server.address"))
                || !"true".equalsIgnoreCase(environment.getProperty("local-canary.ready"))
                || !config.isEnabled() || !CanaryGrant.validKey(config.getSigningKey())
                || !CanaryGrant.validLabel(service) || !CanaryGrant.validLabel(tag) || "stable".equals(tag)
                || !(service + "-canary-" + tag).equals(destination)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        }
        return CanaryGrant.issue(service, tag, Instant.now().plusSeconds(45), config.getSigningKey());
    }

    private boolean loopback(InetSocketAddress address) {
        return address != null && address.getAddress() != null && address.getAddress().isLoopbackAddress();
    }

    private boolean localAuthority(List<String> values) {
        if (values == null || values.size() != 1) return false;
        try {
            URI authority = URI.create("http://" + values.get(0));
            return localHost(authority.getHost()) && authority.getRawUserInfo() == null
                    && authority.getRawQuery() == null && authority.getRawFragment() == null
                    && (authority.getRawPath() == null || authority.getRawPath().isEmpty());
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private boolean localOrigin(List<String> values) {
        if (values == null) return true;
        if (values.size() != 1) return false;
        try {
            URI origin = URI.create(values.get(0));
            return ("http".equals(origin.getScheme()) || "https".equals(origin.getScheme()))
                    && localHost(origin.getHost()) && origin.getRawUserInfo() == null
                    && origin.getRawQuery() == null && origin.getRawFragment() == null
                    && (origin.getRawPath() == null || origin.getRawPath().isEmpty());
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private boolean localHost(String host) {
        return "127.0.0.1".equals(host) || "::1".equals(host) || "[::1]".equals(host)
                || "localhost".equalsIgnoreCase(host);
    }
}

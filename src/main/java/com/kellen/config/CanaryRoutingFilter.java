package com.kellen.config;

import com.kellen.traffic.CanaryGrant;
import com.kellen.observability.fleet.TrafficRouteObservation;
import io.opentelemetry.api.trace.Span;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.RouteToRequestUrlFilter;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_SCHEME_PREFIX_ATTR;

/** 验证灰度凭证后，在负载均衡前切换到独立的灰度服务注册名。 */
@Component
public class CanaryRoutingFilter implements GlobalFilter, Ordered {

    static final String ROUTE_MODE = CanaryRoutingFilter.class.getName() + ".mode";

    private static final Pattern SERVICE_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");

    private final GatewayTrafficGovernanceProperties properties;

    public CanaryRoutingFilter(GatewayTrafficGovernanceProperties properties) {
        this.properties = properties;
    }

    @Override
    public int getOrder() {
        return RouteToRequestUrlFilter.ROUTE_TO_URL_FILTER_ORDER + 1;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        GatewayTrafficGovernanceProperties.Canary config = properties.getCanary();
        List<String> grants = exchange.getAttribute(TrafficGovernanceHeaderFilter.CANARY_GRANTS_ATTRIBUTE);
        URI target = exchange.getAttribute(GATEWAY_REQUEST_URL_ATTR);
        String service = target == null ? null : target.getHost();
        Map<String, Map<String, String>> routes = config.getRoutes();
        if (grants == null) {
            if (isCanaryService(service)) {
                return forbidden(exchange);
            }
            if (service != null && config.isEnabled() && routes.containsKey(service)) {
                exchange.getAttributes().put(ROUTE_MODE, "stable");
            }
            return chain.filter(exchange);
        }
        if (!config.isEnabled() || grants.size() != 1 || service == null || isCanaryService(service)
                || !isLoadBalanced(exchange, target)) {
            return forbidden(exchange);
        }
        String token = grants.get(0);
        CanaryGrant grant = CanaryGrant.verify(token, config.getSigningKey(), Instant.now());
        if (grant == null || !service.equals(grant.service())) {
            return forbidden(exchange);
        }
        String destination = routes.getOrDefault(service, Map.of()).get(grant.tag());
        if (!isMappedCanary(service, grant.tag(), destination) || isStableDestination(destination, routes)) {
            return forbidden(exchange);
        }

        exchange.getAttributes().put(ROUTE_MODE, "canary");
        Span ingressSpan = Span.current();
        URI canaryTarget = UriComponentsBuilder.fromUri(target).host(destination).build(true).toUri();
        exchange.getAttributes().put(GATEWAY_REQUEST_URL_ATTR, canaryTarget);
        ServerHttpRequest forwarded = exchange.getRequest().mutate().headers(headers ->
                headers.set(CanaryGrant.HEADER, token)).build();
        return chain.filter(exchange.mutate().request(forwarded).build())
                .onErrorResume(NotFoundException.class, unavailable -> {
                    TrafficRouteObservation.record(ingressSpan, "gateway", "canary", "unavailable");
                    exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
                    return exchange.getResponse().setComplete();
                });
    }

    private boolean isLoadBalanced(ServerWebExchange exchange, URI target) {
        return "lb".equals(target.getScheme()) || "lb".equals(exchange.getAttribute(GATEWAY_SCHEME_PREFIX_ATTR));
    }

    private boolean isMappedCanary(String service, String tag, String destination) {
        return destination != null && SERVICE_ID.matcher(destination).matches()
                && destination.equals(service + "-canary-" + tag);
    }

    private boolean isCanaryService(String service) {
        return service != null && service.toLowerCase(java.util.Locale.ROOT).contains("-canary-");
    }

    private boolean isStableDestination(String service, Map<String, Map<String, String>> routes) {
        return routes.keySet().stream().anyMatch(service::equalsIgnoreCase);
    }

    private Mono<Void> forbidden(ServerWebExchange exchange) {
        TrafficRouteObservation.record("gateway", "canary", "rejected");
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        return exchange.getResponse().setComplete();
    }
}

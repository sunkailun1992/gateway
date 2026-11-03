package com.kellen.config;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * 路由前统一采用 Spring CORS 处理器；只有显式列出的 HTTP(S) origin 可以跨域。
 *
 * <p>每次请求从当前环境绑定策略，以支持 Nacos 刷新。空配置、通配符或非法配置一律拒绝跨域，
 * 同源及无 Origin 请求保持正常路由；本过滤器不解析身份、不改写 Authorization。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorsFilter extends CorsWebFilter {
    private static final List<String> DEFAULT_METHODS = List.of("GET", "HEAD", "POST", "PUT", "DELETE", "PATCH", "OPTIONS");
    private static final List<String> DEFAULT_HEADERS = List.of("Authorization", "Content-Type", "Accept",
            "X-Requested-With", "traceId", "X-Trace-Id", "X-Request-Id", "X-Release-Version",
            "X-Traffic-Lane", "X-Canary-Tag", "X-Canary-Weight");
    private static final List<String> DEFAULT_EXPOSED_HEADERS = List.of("Authorization", "traceId", "X-Trace-Id");

    public CorsFilter(Environment environment) {
        super(exchange -> configuration(environment));
    }

    private static CorsConfiguration configuration(Environment environment) {
        CorsConfiguration denied = new CorsConfiguration();
        try {
            Binder binder = Binder.get(environment);
            List<String> origins = values(binder, "allowed-origins", List.of()).stream()
                    .map(CorsFilter::origin).toList();
            CorsConfiguration configuration = new CorsConfiguration();
            configuration.setAllowedOrigins(origins);
            configuration.setAllowedMethods(tokens(values(binder, "allowed-methods", DEFAULT_METHODS))
                    .stream().map(value -> value.toUpperCase(Locale.ROOT)).toList());
            configuration.setAllowedHeaders(tokens(values(binder, "allowed-headers", DEFAULT_HEADERS)));
            configuration.setExposedHeaders(tokens(values(binder, "exposed-headers", DEFAULT_EXPOSED_HEADERS)));
            configuration.setAllowCredentials(binder.bind("gateway.cors.allow-credentials", Boolean.class).orElse(true));
            long maxAge = binder.bind("gateway.cors.max-age", Long.class).orElse(1800L);
            if (maxAge < 0 || maxAge > 86400) throw new IllegalArgumentException("Invalid CORS max-age");
            configuration.setMaxAge(maxAge);
            configuration.validateAllowCredentials();
            return configuration;
        } catch (RuntimeException invalidConfiguration) {
            // 配置不可解析时不能退回反射 Origin；空策略由官方处理器返回 403。
            return denied;
        }
    }

    private static List<String> values(Binder binder, String key, List<String> defaults) {
        return binder.bind("gateway.cors." + key, Bindable.listOf(String.class)).orElse(defaults);
    }

    /** Origin 必须是无凭据、路径、query 或 fragment 的完整 origin，不能配置 null 或通配符。 */
    private static String origin(String configured) {
        URI uri = URI.create(configured.trim());
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535
                || !(uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()))) {
            throw new IllegalArgumentException("Invalid CORS allowed origin");
        }
        return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getRawAuthority();
    }

    private static List<String> tokens(List<String> values) {
        return values.stream().map(String::trim).peek(value -> {
            if (value.equals("*") || !value.matches("[A-Za-z0-9!#$%&'+.^_`|~-]{1,128}")) {
                throw new IllegalArgumentException("Invalid CORS method or header");
            }
        }).toList();
    }
}

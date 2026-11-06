package com.kellen.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文档开关未配置时按运行 profile 取默认值：prod 拦截、其它 profile 放行；Nacos 显式配置优先于默认值。
 */
class SensitiveEndpointBlockFilterTest {

    @Test
    void apiDocsBlockedByDefaultUnderProdProfile() {
        Outcome outcome = request("/report/v3/api-docs", null, "prod");
        assertThat(outcome.forwarded()).isFalse();
        assertThat(outcome.status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void apiDocsForwardedByDefaultUnderTestProfile() {
        assertThat(request("/report/v3/api-docs", null, "test").forwarded()).isTrue();
    }

    @Test
    void explicitEnableOverridesProdDefault() {
        assertThat(request("/report/v3/api-docs", true, "prod").forwarded()).isTrue();
    }

    @Test
    void explicitDisableOverridesTestDefault() {
        Outcome outcome = request("/report/v3/api-docs", false, "test");
        assertThat(outcome.forwarded()).isFalse();
        assertThat(outcome.status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test void encodedCustomManagementBaseCannotBypassPublicBlock() {
        MockEnvironment environment = new MockEnvironment().withProperty("management.endpoints.web.base-path", "/ops");
        SensitiveEndpointBlockFilter filter = new SensitiveEndpointBlockFilter(new GatewayExposureProperties(), environment);
        for (String path : new String[]{"/%6fps/prometheus", "/report/ops/prometheus", "/report/%6fps/prometheus"}) {
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.method(org.springframework.http.HttpMethod.GET, java.net.URI.create(path)));
            AtomicBoolean forwarded = new AtomicBoolean();
            filter.filter(exchange, ignored -> { forwarded.set(true); return Mono.empty(); }).block();
            assertThat(forwarded.get()).as(path).isFalse();
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    private static Outcome request(String path, Boolean apiDocsEnabled, String profile) {
        GatewayExposureProperties properties = new GatewayExposureProperties();
        properties.setApiDocsEnabled(apiDocsEnabled);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        SensitiveEndpointBlockFilter filter = new SensitiveEndpointBlockFilter(properties, environment);

        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.method(org.springframework.http.HttpMethod.GET, java.net.URI.create(path)));
        AtomicBoolean forwarded = new AtomicBoolean();
        filter.filter(exchange, ignored -> {
            forwarded.set(true);
            return Mono.empty();
        }).block();
        return new Outcome(forwarded.get(), exchange.getResponse().getStatusCode());
    }

    private record Outcome(boolean forwarded, HttpStatusCode status) {
    }
}

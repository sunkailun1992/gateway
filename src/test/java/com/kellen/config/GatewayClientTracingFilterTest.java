package com.kellen.config;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import org.springframework.http.HttpStatus;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter;
import io.opentelemetry.instrumentation.api.instrumenter.SpanKindExtractor;
import io.opentelemetry.instrumentation.api.semconv.http.HttpClientAttributesExtractor;
import io.opentelemetry.instrumentation.api.semconv.http.HttpClientAttributesGetter;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.setAlreadyRouted;

/** 验证真实发送前注入与整段写回终态，不记录动态目标、认证头或正文。 */
class GatewayClientTracingFilterTest {
    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
    private SdkTracerProvider provider;
    private GatewayClientTracingFilter filter;
    private MockServerWebExchange exchange;

    @BeforeEach
    void setup() {
        GlobalOpenTelemetry.resetForTest();
        provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
        GlobalOpenTelemetry.set(OpenTelemetrySdk.builder().setTracerProvider(provider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance())).build());
        filter = new GatewayClientTracingFilter();
        exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/private-record?secret=synthetic")
                .header(HttpHeaders.AUTHORIZATION, "Bearer synthetic-secret"));
        exchange.getAttributes().put(GATEWAY_REQUEST_URL_ATTR, URI.create("https://unlisted.example.test/private?secret=synthetic"));
    }

    @AfterEach
    void cleanup() { provider.close(); GlobalOpenTelemetry.resetForTest(); }

    @Test
    void completeInjectsRealClientUnderInboundParentAndNeverCollectsPrivateMetadata() {
        Span server = GlobalOpenTelemetry.getTracer("fixture").spanBuilder("fixture.server")
                .setSpanKind(SpanKind.SERVER).startSpan();
        try (Scope ignored = server.makeCurrent()) {
            observe(current -> {
                setAlreadyRouted(current);
                HttpHeaders headers = filter.filter(current.getRequest().getHeaders(), current);
                assertThat(headers.getFirst("traceparent")).contains(server.getSpanContext().getTraceId());
                assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer synthetic-secret");
                upstreamStatus(current, HttpStatus.OK);
                return Mono.empty();
            }).block();
        } finally { server.end(); }
        SpanData client = clientSpans().get(0);
        assertThat(client.getParentSpanId()).isEqualTo(server.getSpanContext().getSpanId());
        assertThat(client.getAttributes().get(AttributeKey.stringKey("business.outcome"))).isEqualTo("success");
        assertThat(client.toString()).doesNotContain("unlisted.example", "private-record", "synthetic-secret", "secret=");
        assertThat(clientSpans()).hasSize(1);
    }

    @Test
    void cancelAfterHeadersEndsExactlyOnceWithoutReportingSuccessOrError() {
        var disposable = observe(current -> {
            filter.filter(current.getRequest().getHeaders(), current);
            return Mono.never();
        }).subscribe();
        disposable.dispose();
        disposable.dispose();
        assertThat(clientSpans()).hasSize(1);
        SpanData span = clientSpans().get(0);
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("business.outcome"))).isEqualTo("cancelled");
    }

    @Test
    void cancellationBeforeUpstreamHeadersDoesNotInventAnHttpResponseStatus() {
        var disposable = observe(current -> {
            filter.filter(current.getRequest().getHeaders(), current);
            current.getResponse().setStatusCode(HttpStatus.OK);
            return Mono.never();
        }).subscribe();
        disposable.dispose();
        assertThat(clientSpans()).hasSize(1);
        assertThat(clientSpans().get(0).getAttributes().get(AttributeKey.longKey("http.response.status_code"))).isNull();
        assertThat(clientSpans().get(0).getAttributes().get(AttributeKey.stringKey("business.outcome"))).isEqualTo("cancelled");
    }

    @Test
    void transportErrorEndsOnceAndKeepsOnlyExceptionClass() {
        assertThatThrownBy(() -> observe(current -> {
            filter.filter(current.getRequest().getHeaders(), current);
            return Mono.error(new IllegalStateException("Bearer synthetic-private-body"));
        }).block()).isInstanceOf(IllegalStateException.class);
        assertThat(clientSpans()).hasSize(1);
        SpanData span = clientSpans().get(0);
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("business.outcome"))).isEqualTo("error");
        assertThat(span.getEvents()).isEmpty();
        assertThat(span.toString()).doesNotContain("synthetic-private-body");
    }

    @Test
    void noOutgoingHttpRequestDoesNotInventAClientSpan() {
        filter.filter(exchange, current -> Mono.empty()).block();
        assertThat(clientSpans()).isEmpty();
    }

    @Test
    void alreadyRoutedLocalResponseNeverCreatesAClientEvenWithAnHttpTarget() {
        setAlreadyRouted(exchange);
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        filter.filter(exchange, current -> filter.gatewayClientSendingBoundary().filter(current, routed -> {
            filter.filter(routed.getRequest().getHeaders(), routed);
            return Mono.empty();
        })).block();
        assertThat(clientSpans()).isEmpty();
    }

    @Test
    void unmanagedLaunchDefaultsToExistingAutomaticInstrumentation() {
        new ApplicationContextRunner().withUserConfiguration(GatewayClientTracingFilter.class)
                .run(context -> assertThat(context).doesNotHaveBean(GatewayClientTracingFilter.class)
                        .doesNotHaveBean("gatewayClientSendingBoundary").doesNotHaveBean("gatewayClientRequestContextWriter"));
    }

    @Test
    void cancelledBodyIsCancelledEvenWhenGatewayWriteMonoCompletesNormally() {
        observe(current -> {
            filter.filter(current.getRequest().getHeaders(), current);
            current.getResponse().setStatusCode(HttpStatus.OK);
            var writing = current.getResponse().writeWith(Flux.never()).subscribe();
            writing.dispose();
            return Mono.empty();
        }).block();
        assertThat(clientSpans()).hasSize(1);
        assertThat(clientSpans().get(0).getAttributes().get(AttributeKey.stringKey("business.outcome"))).isEqualTo("cancelled");
    }

    @Test
    void actualUpstreamErrorIsNotMaskedByResponseBodyCleanupCancellation() {
        assertThatThrownBy(() -> observe(current -> {
            filter.filter(current.getRequest().getHeaders(), current);
            var writing = current.getResponse().writeWith(Flux.never()).subscribe();
            writing.dispose();
            return Mono.error(new IllegalStateException("synthetic-upstream-failure"));
        }).block()).isInstanceOf(IllegalStateException.class);
        assertThat(clientSpans()).hasSize(1);
        assertThat(clientSpans().get(0).getAttributes().get(AttributeKey.stringKey("business.outcome"))).isEqualTo("error");
    }

    @Test
    void repeatedHeaderFilteringUsesOneOwnedSpanAndHttpFailureStaysError() {
        observe(current -> {
            var first = filter.filter(current.getRequest().getHeaders(), current);
            var second = filter.filter(first, current);
            assertThat(first.getFirst("traceparent")).isEqualTo(second.getFirst("traceparent"));
            upstreamStatus(current, HttpStatus.BAD_GATEWAY);
            return Mono.empty();
        }).block();
        assertThat(clientSpans()).hasSize(1);
        assertThat(clientSpans().get(0).getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    }

    @Test
    void httpClientFourHundredResponsesAreErrorsRatherThanSuccessfulBusinessCalls() {
        for (HttpStatus status : List.of(HttpStatus.NOT_FOUND, HttpStatus.TOO_MANY_REQUESTS)) {
            exporter.reset();
            observe(current -> {
                filter.filter(current.getRequest().getHeaders(), current);
                upstreamStatus(current, status);
                return Mono.empty();
            }).block();
            assertThat(clientSpans()).hasSize(1);
            assertThat(clientSpans().get(0).getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
            assertThat(clientSpans().get(0).getAttributes().get(AttributeKey.stringKey("business.outcome"))).isEqualTo("error");
        }
    }

    @Test
    void existingHttpClientContextIsSuppressedAndNeverEndedByThisExchange() {
        var attributes = new HttpClientAttributesGetter<String, Integer>() {
            @Override public String getHttpRequestMethod(String request) { return "GET"; }
            @Override public List<String> getHttpRequestHeader(String request, String name) { return List.of(); }
            @Override public Integer getHttpResponseStatusCode(String request, Integer response, Throwable error) { return response; }
            @Override public List<String> getHttpResponseHeader(String request, Integer response, String name) { return List.of(); }
            @Override public String getUrlFull(String request) { return null; }
            @Override public String getServerAddress(String request) { return null; }
            @Override public Integer getServerPort(String request) { return null; }
        };
        Instrumenter<String, Integer> outer = Instrumenter.<String, Integer>builder(GlobalOpenTelemetry.get(), "fixture", ignored -> "outer")
                .addAttributesExtractor(HttpClientAttributesExtractor.create(attributes))
                .buildInstrumenter(SpanKindExtractor.alwaysClient());
        Context parent = outer.start(Context.root(), "GET");
        try (Scope ignored = parent.makeCurrent()) {
            observe(current -> {
                assertThat(filter.filter(current.getRequest().getHeaders(), current).getFirst("traceparent"))
                        .contains(Span.fromContext(parent).getSpanContext().getSpanId());
                return Mono.empty();
            }).block();
        }
        assertThat(clientSpans()).isEmpty();
        outer.end(parent, "GET", 200, null);
        assertThat(clientSpans()).hasSize(1);
        assertThat(clientSpans().get(0).getName()).isEqualTo("outer");
    }

    private static void upstreamStatus(org.springframework.web.server.ServerWebExchange current, HttpStatus status) {
        current.getResponse().setStatusCode(status);
        var response = org.mockito.Mockito.mock(reactor.netty.http.client.HttpClientResponse.class);
        org.mockito.Mockito.when(response.status()).thenReturn(io.netty.handler.codec.http.HttpResponseStatus.valueOf(status.value()));
        current.getAttributes().put(org.springframework.cloud.gateway.support.ServerWebExchangeUtils.CLIENT_RESPONSE_ATTR, response);
    }

    private Mono<Void> observe(GatewayFilterChain chain) {
        return filter.filter(exchange, current -> filter.gatewayClientSendingBoundary().filter(current, chain));
    }

    private List<SpanData> clientSpans() {
        return exporter.getFinishedSpanItems().stream().filter(span -> span.getKind() == SpanKind.CLIENT).toList();
    }
}

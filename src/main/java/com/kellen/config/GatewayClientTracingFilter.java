package com.kellen.config;

import com.kellen.observability.fleet.TrafficRouteObservation;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter;
import io.opentelemetry.instrumentation.api.instrumenter.SpanKindExtractor;
import io.opentelemetry.instrumentation.api.semconv.http.HttpClientAttributesExtractor;
import io.opentelemetry.instrumentation.api.semconv.http.HttpClientAttributesGetter;
import io.opentelemetry.instrumentation.reactor.v3_1.ContextPropagationOperator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter;
import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.core.Ordered;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.reactivestreams.Publisher;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;
import reactor.netty.http.client.HttpClientResponse;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.ReferenceCountUtil;

import java.net.URI;
import java.util.Set;
import java.util.List;
import java.util.concurrent.CancellationException;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.CLIENT_RESPONSE_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.isAlreadyRouted;

/**
 * 管理真实 HTTP 发送至响应体写回的出站 span 生命周期。
 *
 * <p>Java agent 2.33.1 的 Reactor Netty 客户端在 Gateway 分段写回取消时可能漏掉终态。
 * 公共 Instrumenter 的 HTTP 语义抑制避免下层重复 CLIENT，自动 SERVER 采集保持。
 * 统一启动入口仅关闭 reactor-netty-1.0 客户端模块，保留通用 Netty 与 WebFlux 模块。
 * 不建立应用 SDK，不依赖 agent 内部类或事后伪造缺失的 parent。</p>
 */
@Component
@ConditionalOnProperty(prefix = "gateway.observability", name = "client-span-enabled", havingValue = "true")
public final class GatewayClientTracingFilter implements GlobalFilter, HttpHeadersFilter, Ordered {
    private static final String STATE = GatewayClientTracingFilter.class.getName() + ".state";
    private static final Object REQUEST_STATE = new Object();
    private static final String WRITE_HANDLER = GatewayClientTracingFilter.class.getName() + ".request-context";
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "CONNECT", "TRACE");
    private final Instrumenter<String, Integer> instrumenter = Instrumenter.<String, Integer>builder(
                    GlobalOpenTelemetry.get(), "com.kellen.gateway.http", method -> "gateway.http.client")
            .addAttributesExtractor(HttpClientAttributesExtractor.create(new SafeHttpAttributes()))
            .buildInstrumenter(SpanKindExtractor.alwaysClient());

    /** 在负载均衡已解析目标之后、真实 NettyRoutingFilter 之前建立客户端上下文。 */
    @Bean
    GlobalFilter gatewayClientSendingBoundary() { return new SendingBoundary(); }

    /**
     * POST 请求头的真正 write 可能由入站 body 回调触发，不能只依赖组装/连接时的 Scope。
     * 每个请求从 Reactor Context 取得自己的状态，只包住同步 HttpRequest write。
     */
    @Bean
    @Order(0)
    HttpClientCustomizer gatewayClientRequestContextWriter() {
        return httpClient -> httpClient.doOnRequest((request, connection) -> {
            ExchangeTrace state = request.currentContextView().getOrDefault(REQUEST_STATE, null);
            if (state == null) return;
            Context client = state.writingContext();
            var existing = connection.channel().pipeline().get(WRITE_HANDLER);
            if (existing != null) {
                if (existing instanceof RequestContextWriter writer && writer.state == state) return;
                // 不替换其他请求或未知用户 handler；池/stream 归属异常时失败关闭。
                throw new IllegalStateException("Gateway request context handler conflict");
            }
            // 必须使用 Connection API：它会在请求终止/池释放时安全移除尚未写头的 handler。
            // 使用 Reactor Netty 提供的请求 Connection，不把 handler 主动挂到其 parent。
            connection.addHandlerLast(WRITE_HANDLER, new RequestContextWriter(state, client));
        });
    }

    private static final class RequestContextWriter extends ChannelOutboundHandlerAdapter {
        private final ExchangeTrace state;
        private final Context client;

        private RequestContextWriter(ExchangeTrace state, Context client) {
            this.state = state;
            this.client = client;
        }

        @Override
        public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
            if (!(message instanceof HttpRequest)) {
                context.write(message, promise);
                return;
            }
            context.pipeline().remove(this);
            try { state.writingContext(); }
            catch (IllegalStateException failure) {
                ReferenceCountUtil.release(message);
                promise.tryFailure(failure);
                return;
            }
            try (Scope ignored = client.makeCurrent()) { context.write(message, promise); }
        }
    }

    private final class SendingBoundary implements GlobalFilter, Ordered {
        @Override
        public int getOrder() { return Integer.MAX_VALUE - 1; }

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
            return Mono.defer(() -> {
                ExchangeTrace state = exchange.getAttribute(STATE);
                URI target = exchange.getAttribute(GATEWAY_REQUEST_URL_ATTR);
                if (state == null || isAlreadyRouted(exchange) || !http(target)) return chain.filter(exchange);
                Context client = state.start(exchange.getRequest().getMethod().name());
                String mode = exchange.getAttribute(CanaryRoutingFilter.ROUTE_MODE);
                if (mode != null) {
                    TrafficRouteObservation.record(Span.fromContext(client), "gateway", mode, "selected");
                }
                // 使用公开 Reactor bridge 同时覆盖订阅与异步回调，Scope 只在当前线程同步使用。
                return ContextPropagationOperator.runWithContext(Mono.defer(() -> {
                    try (Scope ignored = client.makeCurrent()) { return chain.filter(exchange); }
                }), client).contextWrite(context -> context.put(REQUEST_STATE, state));
            });
        }
    }

    @Override
    public int getOrder() {
        // 必须包住 order=-1 的 NettyWriteResponseFilter，不能在仅收到响应头时提前结束。
        return -2;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return Mono.defer(() -> {
            ExchangeTrace state = new ExchangeTrace(Context.current(), instrumenter);
            exchange.getAttributes().put(STATE, state);
            var response = new ServerHttpResponseDecorator(exchange.getResponse()) {
                @Override
                public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                    return super.writeWith(Flux.from(body).doOnCancel(state::cancel));
                }

                @Override
                public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
                    return super.writeAndFlushWith(Flux.from(body)
                            .map(chunk -> Flux.from(chunk).doOnCancel(state::cancel))
                            .doOnCancel(state::cancel));
                }
            };
            return Mono.defer(() -> chain.filter(exchange.mutate().response(response).build()))
                    .doOnError(state::error)
                    .doFinally(signal -> state.finish(exchange, signal));
        });
    }

    @Override
    public HttpHeaders filter(HttpHeaders input, ServerWebExchange exchange) {
        ExchangeTrace state = exchange.getAttribute(STATE);
        URI target = exchange.getAttribute(GATEWAY_REQUEST_URL_ATTR);
        if (state == null || !http(target)) {
            return input;
        }
        // 此官方扩展点位于实际 NettyRoutingFilter 发请求之前，且已完成 lb:// 解析。
        // NettyRoutingFilter 此时已设置 alreadyRouted；只为实际发送边界已启动的状态注入，
        // 不能由 header helper 给本地响应/缓存短路补建 CLIENT。
        Context client = state.sendingContext();
        if (client == null) return input;
        HttpHeaders output = new HttpHeaders();
        output.addAll(input);
        output.remove("traceparent");
        output.remove("tracestate");
        W3CTraceContextPropagator.getInstance().inject(client, output, HttpHeaders::set);
        return output;
    }

    @Override
    public boolean supports(Type type) { return type == Type.REQUEST; }

    private static boolean http(URI target) {
        return target != null && ("http".equalsIgnoreCase(target.getScheme()) || "https".equalsIgnoreCase(target.getScheme()));
    }

    /** 只暴露固定方法和状态；即使上层配置了 header capture 也读取不到业务头或 URL。 */
    private static final class SafeHttpAttributes implements HttpClientAttributesGetter<String, Integer> {
        @Override public String getHttpRequestMethod(String method) { return method; }
        @Override public List<String> getHttpRequestHeader(String method, String name) { return List.of(); }
        @Override public Integer getHttpResponseStatusCode(String method, Integer status, Throwable error) { return status; }
        @Override public List<String> getHttpResponseHeader(String method, Integer status, String name) { return List.of(); }
        @Override public String getUrlFull(String method) { return null; }
        @Override public String getServerAddress(String method) { return null; }
        @Override public Integer getServerPort(String method) { return null; }
    }

    private static final class ExchangeTrace {
        private final Context parent;
        private final Instrumenter<String, Integer> instrumenter;
        private Span span;
        private String method;
        private Context client;
        private String errorClass;
        private boolean cancelled;
        private boolean finished;

        private ExchangeTrace(Context parent, Instrumenter<String, Integer> instrumenter) {
            this.parent = parent;
            this.instrumenter = instrumenter;
        }

        private synchronized Context start(String method) {
            if (finished) throw new CancellationException("Gateway exchange already terminated");
            if (client == null) {
                this.method = METHODS.contains(method) ? method : "_OTHER";
                if (instrumenter.shouldStart(parent, this.method)) {
                    client = instrumenter.start(parent, this.method);
                    span = Span.fromContext(client);
                } else {
                    // 遵守上层 HTTP Instrumenter 的抑制合同，绝不能结束别人的 span。
                    client = parent;
                }
            }
            return client;
        }

        private synchronized void error(Throwable error) { errorClass = error.getClass().getName(); }

        private synchronized void cancel() { cancelled = true; }

        private synchronized Context sendingContext() { return client; }

        private synchronized Context writingContext() {
            if (finished) throw new CancellationException("Gateway exchange already terminated");
            if (client == null) throw new IllegalStateException("Gateway sending boundary missing");
            return client;
        }

        private synchronized void finish(ServerWebExchange exchange, SignalType signal) {
            if (finished) return;
            finished = true;
            if (span == null) return;
            HttpClientResponse response = exchange.getAttribute(CLIENT_RESPONSE_ATTR);
            // 下游响应对象可能默认 200；没有真正收到上游响应头时，CLIENT 状态码仍未知。
            int status = response == null ? 0 : response.status().code();
            if (status >= 100 && status <= 599) span.setAttribute("http.response.status_code", (long) status);
            String outcome = errorClass != null || status >= 400 ? "error"
                    : signal == SignalType.CANCEL || cancelled ? "cancelled" : "success";
            span.setAttribute("business.outcome", outcome);
            if ("error".equals(outcome)) {
                span.setStatus(StatusCode.ERROR);
                if (errorClass != null) span.setAttribute("error.type", errorClass);
            }
            // 不调用 recordException，不采集 URL、地址、认证、请求体或异常正文。
            instrumenter.end(client, method, status == 0 ? null : status, null);
        }
    }
}

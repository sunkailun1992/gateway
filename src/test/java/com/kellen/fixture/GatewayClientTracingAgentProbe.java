package com.kellen.fixture;

import io.opentelemetry.api.trace.Span;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.ClosedChannelException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** 子 JVM 专用的真实 POST/WebFlux/Gateway；只使用合成数据与回环端口。 */
public final class GatewayClientTracingAgentProbe {
    private static final String BODY = "{\"input\":\"synthetic\"}";
    private static final String AUTH = "Bearer synthetic-tracing-fixture";
    private static final String WRITER = "com.kellen.config.GatewayClientTracingFilter.request-context";
    private static final CountDownLatch CANCELLED = new CountDownLatch(1);
    private static final CountDownLatch HEAD_STALLED = new CountDownLatch(1);
    private static final CountDownLatch EARLY_REMOVED = new CountDownLatch(1);
    private static final CountDownLatch EARLY_TERMINATED = new CountDownLatch(1);
    private static final CountDownLatch EARLY_INBOUND_ENDED = new CountDownLatch(1);
    private static final AtomicBoolean GATE = new AtomicBoolean();
    private static final AtomicBoolean GATE_REMOVED = new AtomicBoolean();
    private static final AtomicBoolean CONTEXT_CLEANED = new AtomicBoolean();
    private static final AtomicInteger UPSTREAM_CALLS = new AtomicInteger();
    private static int upstreamPort;
    private static String mode;

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(com.kellen.config.GatewayClientTracingFilter.class)
    @ConditionalOnProperty(name = "test.gateway.tracing.fixture", havingValue = "true")
    static class Gateway {
        @Bean RouteLocator route(RouteLocatorBuilder routes) {
            return routes.routes().route("fixture-stream", route -> route.path("/api/stream")
                    .uri("http://127.0.0.1:" + upstreamPort)).build();
        }

        @Bean WebFilter gatewayObserved() {
            return (exchange, chain) -> {
                printCurrent("GATEWAY_SERVER");
                boolean early = "true".equals(exchange.getRequest().getHeaders().getFirst("X-Fixture-Early"));
                return chain.filter(exchange).doFinally(signal -> {
                    if (early) {
                        System.out.println("EARLY_INBOUND_SIGNAL=" + signal);
                        EARLY_INBOUND_ENDED.countDown();
                    }
                });
            };
        }

        @Bean @Order(Ordered.LOWEST_PRECEDENCE)
        HttpClientCustomizer fixtureConnectionAndEarlyCancel() {
            return client -> client.doOnRequest((request, connection) -> {
                System.out.println("REQUEST_CHANNEL=" + connection.channel().id().asShortText());
                if (GATE.compareAndSet(true, false)) {
                    connection.addHandlerLast("fixture.before-head", new EarlyGate());
                    connection.onTerminate().subscribe(null, failure -> EARLY_TERMINATED.countDown(), () -> {
                        System.out.println("EARLY_CONNECTION_TERMINATED=true");
                        System.out.println("EARLY_TERMINATE_ACTIVE=" + connection.channel().isActive());
                        System.out.println("EARLY_TERMINATE_GATE_REMOVED=" + GATE_REMOVED.get());
                        EARLY_TERMINATED.countDown();
                    });
                }
            });
        }
    }

    /** 不阻塞 event loop：暂存真实头 write，等客户端取消触发框架的 onTerminate 移除。 */
    private static final class EarlyGate extends ChannelOutboundHandlerAdapter {
        private final ArrayList<Object[]> pending = new ArrayList<>();
        private boolean stalled;

        @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
            if (message instanceof io.netty.handler.codec.http.HttpRequest || stalled) {
                if (!stalled && context.pipeline().get(WRITER) == null) throw new AssertionError("fixture-gate-order");
                stalled = true;
                if (pending.size() >= 16) throw new AssertionError("fixture-gate-bound");
                pending.add(new Object[]{message, promise});
                HEAD_STALLED.countDown();
            } else context.write(message, promise);
        }

        @Override public void flush(ChannelHandlerContext context) { if (!stalled) context.flush(); }

        @Override public void handlerRemoved(ChannelHandlerContext context) {
            for (Object[] entry : pending) {
                ReferenceCountUtil.release(entry[0]);
                ((ChannelPromise) entry[1]).tryFailure(new ClosedChannelException());
            }
            pending.clear();
            if (stalled) {
                GATE_REMOVED.set(true);
                System.out.println("EARLY_GATE_REMOVED=true");
                // channel 已关闭时，真正移除发生于 pipeline.destroyDown，而非 onTerminate 快照。
                context.executor().execute(() -> {
                    CONTEXT_CLEANED.set(context.pipeline().get(WRITER) == null);
                    EARLY_REMOVED.countDown();
                });
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @ConditionalOnProperty(name = "test.gateway.tracing.fixture", havingValue = "true")
    static class Backend {
        @Bean RouterFunction<ServerResponse> source() {
            return RouterFunctions.route().POST("/api/stream", request -> {
                UPSTREAM_CALLS.incrementAndGet();
                System.out.println("UPSTREAM_PARENT=" + request.headers().firstHeader("traceparent"));
                printCurrent("UPSTREAM_SERVER");
                System.out.println("UPSTREAM_CONNECTION=" + request.exchange().getRequest().getRemoteAddress().getPort());
                return request.bodyToMono(String.class).defaultIfEmpty("").flatMap(body -> {
                    if (!BODY.equals(body) || !AUTH.equals(request.headers().firstHeader("Authorization"))) {
                        throw new AssertionError("fixture-request-changed");
                    }
                    System.out.println("REQUEST_BYTES_PRESERVED=true");
                    Flux<String> source = Flux.interval(Duration.ofMillis(80))
                            .map(index -> "synthetic-token-" + index).doOnCancel(CANCELLED::countDown);
                    if (!mode.equals("cancel")) source = source.take(5);
                    if (mode.equals("error")) source = source.concatWith(Mono.error(new IllegalStateException("synthetic-stream-failure")));
                    return ServerResponse.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(source, String.class);
                });
            }).build();
        }
    }

    private static void printCurrent(String label) {
        var span = Span.current().getSpanContext();
        System.out.println(label + "=" + span.getTraceId() + ":" + span.getSpanId());
    }

    private static SpringApplication app(Class<?> source) {
        var application = new SpringApplication(source);
        application.setDefaultProperties(Map.ofEntries(
                Map.entry("server.port", "0"), Map.entry("server.address", "127.0.0.1"),
                Map.entry("spring.config.name", "isolated-gateway-cancel"),
                Map.entry("spring.main.web-application-type", "reactive"),
                Map.entry("spring.cloud.nacos.config.enabled", "false"),
                Map.entry("spring.cloud.nacos.discovery.enabled", "false"),
                Map.entry("spring.cloud.nacos.config.import-check.enabled", "false"),
                Map.entry("management.endpoints.web.exposure.include", "health"),
                Map.entry("logging.level.root", "WARN"),
                Map.entry("gateway.observability.client-span-enabled", "true"),
                Map.entry("fleet.observability.enabled", "true"),
                Map.entry("test.gateway.tracing.fixture", "true")));
        return application;
    }

    private static HttpRequest request(int port) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/stream"))
                .header("Content-Type", "application/json").header("Authorization", AUTH)
                .POST(HttpRequest.BodyPublishers.ofString(BODY)).build();
    }

    private static void call(HttpClient client, int port) throws Exception {
        var response = client.send(request(port), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) throw new AssertionError("fixture-response-status");
        try (var body = response.body()) {
            if (mode.equals("cancel")) {
                if (body.readNBytes(20).length != 20) throw new AssertionError("fixture-first-frame");
                System.out.println("CLIENT_READ=20");
            } else {
                try {
                    String actual = new String(body.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    StringBuilder expected = new StringBuilder();
                    for (int index = 0; index < 5; index++) expected.append("data:synthetic-token-").append(index).append("\n\n");
                    if (!actual.contentEquals(expected)) throw new AssertionError("fixture-response-changed");
                    System.out.println("RESPONSE_BYTES_PRESERVED=true");
                } catch (java.io.IOException error) {
                    if (!mode.equals("error")) throw error;
                    System.out.println("CLIENT_ERROR=true");
                }
            }
        }
        System.out.println("CLIENT_CLOSED");
        if (mode.equals("cancel")) System.out.println("UPSTREAM_CANCEL=" + CANCELLED.await(5, TimeUnit.SECONDS));
    }

    public static void main(String[] arguments) throws Exception {
        mode = arguments[0];
        try (var backend = app(Backend.class).run()) {
            upstreamPort = ((WebServerApplicationContext) backend).getWebServer().getPort();
            try (var gateway = app(Gateway.class).run()) {
                int port = ((WebServerApplicationContext) gateway).getWebServer().getPort();
                var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
                if (mode.equals("lifecycle")) {
                    for (int index = 0; index < 6; index++) { call(client, port); Thread.sleep(40); }
                    System.out.println("POOLED_CALLS=6");
                    var executor = Executors.newFixedThreadPool(6);
                    try {
                        var jobs = new ArrayList<java.util.concurrent.Future<?>>();
                        for (int index = 0; index < 12; index++) jobs.add(executor.submit(() -> {
                            try { call(client, port); } catch (Exception failure) { throw new RuntimeException(failure); }
                        }));
                        for (var job : jobs) job.get();
                    } finally { executor.shutdownNow(); }
                    System.out.println("CONCURRENT_CALLS=12");
                    GATE.set(true);
                    // 直接关闭真实 TCP 客户端，不依赖被 agent 包装的 JDK Future.cancel 语义。
                    try (var socket = new java.net.Socket()) {
                        socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 5000);
                        socket.setSoLinger(true, 0);
                        byte[] body = BODY.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        String headers = "POST /api/stream HTTP/1.1\r\nHost: 127.0.0.1:" + port
                                + "\r\nContent-Type: application/json\r\nAuthorization: " + AUTH
                                + "\r\nX-Fixture-Early: true\r\nContent-Length: " + body.length + "\r\n\r\n";
                        socket.getOutputStream().write(headers.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                        socket.getOutputStream().write(body);
                        socket.getOutputStream().flush();
                        if (!HEAD_STALLED.await(10, TimeUnit.SECONDS)) throw new AssertionError("fixture-head-not-stalled");
                    }
                    if (!EARLY_INBOUND_ENDED.await(10, TimeUnit.SECONDS)) throw new AssertionError("fixture-client-disconnect-not-received");
                    if (!EARLY_TERMINATED.await(10, TimeUnit.SECONDS)) throw new AssertionError("fixture-connection-not-terminated");
                    if (!EARLY_REMOVED.await(10, TimeUnit.SECONDS) || !CONTEXT_CLEANED.get()) throw new AssertionError("fixture-early-cleanup");
                    if (UPSTREAM_CALLS.get() != 18) throw new AssertionError("fixture-early-request-escaped");
                    System.out.println("EARLY_CONTEXT_CLEANED=true");
                    System.out.println("EARLY_UPSTREAM_CALLS=0");
                    call(client, port);
                    if (UPSTREAM_CALLS.get() != 19) throw new AssertionError("fixture-followup-call");
                    System.out.println("FOLLOWUP_SUCCESS=true");
                } else call(client, port);
                Thread.sleep(2500);
                System.out.println("BEFORE_SHUTDOWN");
            }
        }
        Thread.sleep(1000);
        System.out.println("EXIT");
    }
}

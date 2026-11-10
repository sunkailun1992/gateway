package com.kellen.fixture;

import io.opentelemetry.api.trace.Span;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 子 JVM 专用的两个真实 WebFlux 上下文；所有数据和端口均为合成回环数据。 */
public final class GatewayClientTracingAgentProbe {
    private static int upstreamPort;
    private static String mode;
    private static final CountDownLatch CANCELLED = new CountDownLatch(1);

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(com.kellen.config.GatewayClientTracingFilter.class)
    @ConditionalOnProperty(name = "test.gateway.tracing.fixture", havingValue = "true")
    static class Gateway {
        @Bean
        RouteLocator route(RouteLocatorBuilder routes) {
            return routes.routes().route("fixture-stream", route -> route.path("/api/stream")
                    .uri("http://127.0.0.1:" + upstreamPort)).build();
        }

        @Bean
        WebFilter gatewayObserved() {
            return (exchange, chain) -> {
                printCurrent("GATEWAY_SERVER");
                return chain.filter(exchange);
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @ConditionalOnProperty(name = "test.gateway.tracing.fixture", havingValue = "true")
    static class Backend {
        @Bean
        RouterFunction<ServerResponse> source() {
            return RouterFunctions.route().GET("/api/stream", request -> {
                System.out.println("UPSTREAM_PARENT=" + request.headers().firstHeader("traceparent"));
                printCurrent("UPSTREAM_SERVER");
                Flux<String> source = Flux.interval(Duration.ofMillis(80))
                        .map(index -> "synthetic-token-" + index).doOnCancel(CANCELLED::countDown);
                if (!mode.equals("cancel")) source = source.take(5);
                if (mode.equals("error")) {
                    source = source.concatWith(Mono.error(new IllegalStateException("synthetic-stream-failure")));
                }
                return ServerResponse.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(source, String.class);
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
                Map.entry("test.gateway.tracing.fixture", "true")));
        return application;
    }

    public static void main(String[] arguments) throws Exception {
        mode = arguments[0];
        try (var backend = app(Backend.class).run()) {
            upstreamPort = ((WebServerApplicationContext) backend).getWebServer().getPort();
            try (var gateway = app(Gateway.class).run()) {
                int port = ((WebServerApplicationContext) gateway).getWebServer().getPort();
                var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/stream")).GET().build();
                var response = java.net.http.HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (var body = response.body()) {
                    if (mode.equals("cancel")) {
                        System.out.println("CLIENT_READ=" + body.readNBytes(20).length);
                    } else {
                        try { System.out.println("CLIENT_READ=" + body.readAllBytes().length); }
                        catch (java.io.IOException error) { System.out.println("CLIENT_ERROR=true"); }
                    }
                }
                System.out.println("CLIENT_CLOSED");
                if (mode.equals("cancel")) System.out.println("UPSTREAM_CANCEL=" + CANCELLED.await(5, TimeUnit.SECONDS));
                Thread.sleep(2500);
                System.out.println("BEFORE_SHUTDOWN");
            }
        }
        Thread.sleep(1000);
        System.out.println("EXIT");
    }
}

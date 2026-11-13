package com.kellen.config;

import com.sun.net.httpserver.HttpServer;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 网关集成测试用的假后端：任意路径都返回 200 和 {@code stub:<后端收到的路径>}，并记录收到的路径。
 *
 * <p>被网关拦截的请求不应出现在 {@link #receivedPaths()} 中，以此证明拦截发生在转发之前。</p>
 */
final class StubBackend {

    private final HttpServer server;

    private final List<ReceivedRequest> receivedRequests = new CopyOnWriteArrayList<>();
    private final List<String> receivedPaths = new CopyOnWriteArrayList<>();
    private final List<String> receivedAuthorizations = new CopyOnWriteArrayList<>();
    private volatile Map<String, List<String>> responseHeaders = Map.of();

    private StubBackend(HttpServer server) {
        this.server = server;
    }

    static StubBackend start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            StubBackend backend = new StubBackend(server);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getRawPath();
                backend.receivedPaths.add(path);
                HttpHeaders headers = new HttpHeaders();
                exchange.getRequestHeaders().forEach(headers::addAll);
                backend.receivedRequests.add(new ReceivedRequest(exchange.getRequestURI().toString(),
                        HttpHeaders.readOnlyHttpHeaders(headers)));
                String authorization = exchange.getRequestHeaders().getFirst("Authorization");
                if (authorization != null) backend.receivedAuthorizations.add(authorization);
                backend.responseHeaders.forEach((name, values) -> exchange.getResponseHeaders().put(name, values));
                byte[] body = ("stub:" + path).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            return backend;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * 按线上 {@code gateway-spring.yaml} 的写法注册 report 路由：{@code /report/**} 去掉前缀后转发到假后端。
     */
    void registerReportRoute(DynamicPropertyRegistry registry) {
        String route = "spring.cloud.gateway.server.webflux.routes[0]";
        registry.add(route + ".id", () -> "report");
        registry.add(route + ".uri", () -> "http://127.0.0.1:" + server.getAddress().getPort());
        registry.add(route + ".predicates[0]", () -> "Path=/report/**");
        registry.add(route + ".filters[0]", () -> "RewritePath=/report/(?<segment>.*), /$\\{segment}");
    }

    List<String> receivedPaths() {
        return receivedPaths;
    }

    String uri() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    List<ReceivedRequest> receivedRequests() {
        return receivedRequests;
    }

    record ReceivedRequest(String target, HttpHeaders headers) {
    }

    void clear() {
        receivedRequests.clear();
        receivedPaths.clear();
        receivedAuthorizations.clear();
        responseHeaders = Map.of();
    }

    void respondWithHeaders(Map<String, List<String>> headers) {
        responseHeaders = Map.copyOf(headers);
    }

    List<String> receivedAuthorizations() {
        return receivedAuthorizations;
    }

    void stop() {
        server.stop(0);
    }
}

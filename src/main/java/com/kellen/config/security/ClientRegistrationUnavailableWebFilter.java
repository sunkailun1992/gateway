package com.kellen.config.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 会话链最前面的出口：客户端注册取不到（Keycloak 不可达，见 {@link SupplierReactiveClientRegistrationRepository}）时返回 503 JSON（C0001），
 * 与 TokenRelay 续期连不上 Keycloak 时的响应一致；缺省会变成 500 并打印堆栈。登录入口、登录回调与 TokenRelay 都经过这里。只记一行警告。
 *
 * @author 孙凯伦
 */
final class ClientRegistrationUnavailableWebFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(ClientRegistrationUnavailableWebFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return chain.filter(exchange).onErrorResume(ClientRegistrationUnavailableException.class, failure -> {
            if (exchange.getResponse().isCommitted()) {
                return Mono.error(failure);
            }
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            log.warn("Keycloak 暂不可达，客户端注册还没取到，返回 503：{} {}，{}", exchange.getRequest().getMethod(),
                    exchange.getRequest().getPath(), cause.getClass().getSimpleName() + (cause.getMessage() == null ? "" : "：" + cause.getMessage()));
            return GatewayErrorResponses.write(exchange, HttpStatus.SERVICE_UNAVAILABLE, "C0001", "调用第三方服务出错");
        });
    }
}

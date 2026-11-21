package com.kellen.config.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Mono;

/**
 * 签发方元数据或公钥集暂时取不到时，按「无法校验令牌」拒绝 Bearer 请求（401 JSON），与 utils servlet 侧的 FailClosedJwtDecoder 一致。
 *
 * <p>Spring Boot 按 issuer-uri 建的响应式解码器延迟发现签发方：发现失败抛的不是 {@link JwtException}；公钥集取不到时还可能
 * 什么都不返回，{@code AuthenticationWebFilter} 随即抛 {@code IllegalStateException("No provider found…")}。两种都会以 500 和
 * 堆栈结束。这里统一换成 {@link JwtException}，由标准的 JWT 认证管理器转成认证服务异常、交给 401 入口；只记一行警告，
 * 失败不缓存，签发方恢复后下一个请求照常校验。</p>
 *
 * @author 孙凯伦
 */
final class FailClosedReactiveJwtDecoder implements ReactiveJwtDecoder {

    private static final Logger log = LoggerFactory.getLogger(FailClosedReactiveJwtDecoder.class);

    private final ReactiveJwtDecoder delegate;

    /**
     * 包装解码器。
     *
     * @param delegate Spring Boot 创建的解码器
     */
    FailClosedReactiveJwtDecoder(ReactiveJwtDecoder delegate) {
        this.delegate = delegate;
    }

    @Override
    public Mono<Jwt> decode(String token) {
        return Mono.defer(() -> delegate.decode(token))
                .onErrorMap(failure -> !(failure instanceof JwtException), failure -> {
                    log.warn("校验 Bearer 令牌时取不到签发方元数据或公钥：{}", rootMessage(failure));
                    return new JwtException("签发方元数据或公钥暂时取不到", failure);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("校验 Bearer 令牌时公钥集没有返回结果");
                    return Mono.error(new JwtException("签发方公钥暂时取不到"));
                }));
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName() + (current.getMessage() == null ? "" : "：" + current.getMessage());
    }
}

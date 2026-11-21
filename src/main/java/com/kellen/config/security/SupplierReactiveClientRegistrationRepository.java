package com.kellen.config.security;

import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.util.Assert;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 第一次查找时才构建的客户端注册仓库：按 issuer-uri 取 Keycloak 发现文档推迟到用到的时候，Keycloak 不可达时网关照常启动。
 *
 * <p>写法照 Spring Security 自己的延迟实现：响应式资源服务器按 issuer-uri 的 {@code SupplierReactiveJwtDecoder}
 * （成功的结果一直用、失败不缓存，构建放在 boundedElastic 线程），以及 servlet 端的 {@code SupplierClientRegistrationRepository}；
 * Spring Security 7 没有响应式的客户端注册版本。构建失败统一抛 {@link ClientRegistrationUnavailableException}，下次查找再试，
 * Keycloak 恢复后无需重启。并发的首次查找共用同一次构建。</p>
 *
 * <p>不实现 {@code Iterable<ClientRegistration>}：WebFlux 的 oauth2Login 在装配过滤链时会遍历这类 Bean 生成登录链接，
 * 遍历就等于在启动时发现；网关有自己的未登录入口，用不到这些链接。</p>
 *
 * @author 孙凯伦
 */
final class SupplierReactiveClientRegistrationRepository implements ReactiveClientRegistrationRepository {

    private static final Duration FOREVER = Duration.ofMillis(Long.MAX_VALUE);

    private final Mono<ReactiveClientRegistrationRepository> repository;

    /**
     * 构造仓库。
     *
     * @param supplier 构建委托仓库（阻塞调用，会按 issuer-uri 取发现文档）
     */
    SupplierReactiveClientRegistrationRepository(Supplier<? extends ReactiveClientRegistrationRepository> supplier) {
        Assert.notNull(supplier, "supplier cannot be null");
        this.repository = Mono.<ReactiveClientRegistrationRepository>fromSupplier(supplier)
                .subscribeOn(Schedulers.boundedElastic())
                .publishOn(Schedulers.parallel())
                .onErrorMap(failure -> !(failure instanceof ClientRegistrationUnavailableException),
                        failure -> new ClientRegistrationUnavailableException("按 issuer-uri 取 Keycloak 发现文档失败", failure))
                .cache(delegate -> FOREVER, failure -> Duration.ZERO, () -> Duration.ZERO);
    }

    @Override
    public Mono<ClientRegistration> findByRegistrationId(String registrationId) {
        return repository.flatMap(delegate -> delegate.findByRegistrationId(registrationId));
    }
}

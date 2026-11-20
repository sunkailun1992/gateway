package com.kellen.config.security;

import org.springframework.boot.security.autoconfigure.actuate.web.reactive.EndpointRequest;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.reactive.JwkSetUriReactiveJwtDecoderBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.oidc.web.server.logout.OidcClientInitiatedServerLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.server.DefaultServerOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.server.WebSessionServerOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.DefaultReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.web.server.DelegatingServerAuthenticationEntryPoint;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authentication.logout.WebSessionServerLogoutHandler;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.security.web.server.util.matcher.AndServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.MediaTypeServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.NegatedServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.session.CookieWebSessionIdResolver;
import org.springframework.web.server.session.WebSessionIdResolver;

import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Set;

/**
 * 网关 BFF 的安全配置（Spring Security WebFlux，按请求的凭证分两条过滤链，做法同 Spring Security 文档的多过滤链）：
 *
 * <ul>
 *     <li>Bearer 链：请求带 {@code Authorization: Bearer} 时按 Keycloak 访问令牌校验（签发方、受众、有效期、只收 RS256），
 *     无状态、不建会话、不要 CSRF，通过后原样转发，细粒度授权在各服务。</li>
 *     <li>会话链：其余请求。浏览器经 Keycloak 授权码加 PKCE 登录，会话存 Spring Session（Redis），已授权客户端（访问令牌与
 *     刷新令牌）存在会话里；转发时 TokenRelay 换成 Bearer 头，到期由授权客户端管理器用刷新令牌续期。写请求要带
 *     {@code X-XSRF-TOKEN}（值取自 {@code XSRF-TOKEN} Cookie，Spring Security 单页应用写法）；{@code POST /logout}
 *     结束网关会话并做 Keycloak 的 RP 发起注销。</li>
 * </ul>
 *
 * <p>Actuator 端点由更前面的 {@link com.kellen.config.SensitiveEndpointBlockFilter} 把关（健康检查公开，其余只走管理端口并
 * 校验运维令牌），OpenAPI 文档是否对外也由它决定，这里都放行。未登录时页面跳转去登录，API 与 XHR 请求返回 401 JSON。</p>
 *
 * @author 孙凯伦
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
public class GatewaySecurityConfig {

    /**
     * Keycloak 登录注册（Nacos {@code spring.security.oauth2.client.registration.keycloak}）。
     */
    static final String REGISTRATION_ID = "keycloak";

    /**
     * 登录入口：跳到 Keycloak 的授权端点。
     */
    static final String LOGIN_ENTRY = "/oauth2/authorization/" + REGISTRATION_ID;

    /**
     * 会话 Cookie：{@code __Host-} 前缀要求 Secure、Path=/、不带 Domain（RFC 6265bis）；本机 localhost 属安全上下文。
     */
    static final String SESSION_COOKIE = "__Host-SESSION";

    /**
     * OpenAPI 文档与 Swagger UI（网关自身与按服务前缀转发的文档）；是否对外由 SensitiveEndpointBlockFilter 按开关决定。
     */
    private static final String[] API_DOCS = {"/v3/api-docs", "/v3/api-docs.yaml", "/v3/api-docs/**", "/*/v3/api-docs/**",
            "/swagger-ui.html", "/swagger-ui/**", "/webjars/**"};

    /**
     * 构造安全配置。
     */
    public GatewaySecurityConfig() {
    }

    /**
     * Bearer 链：带 Bearer 令牌的请求。
     *
     * @param http       Spring Security HTTP 配置
     * @param entryPoint 401 JSON
     * @param denied     403 JSON
     * @return 过滤链
     */
    @Bean
    @Order(1)
    SecurityWebFilterChain gatewayBearerFilterChain(ServerHttpSecurity http, ApiAuthenticationEntryPoint entryPoint, ApiAccessDeniedHandler denied) {
        http.securityMatcher(GatewaySecurityConfig::bearerRequest);
        http.authorizeExchange(exchanges -> exchanges
                .matchers(EndpointRequest.toAnyEndpoint()).permitAll()
                .pathMatchers(API_DOCS).permitAll()
                .anyExchange().authenticated());
        http.oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults())
                .authenticationEntryPoint(entryPoint).accessDeniedHandler(denied));
        http.exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(entryPoint).accessDeniedHandler(denied));
        http.securityContextRepository(NoOpServerSecurityContextRepository.getInstance());
        http.requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()));
        http.csrf(ServerHttpSecurity.CsrfSpec::disable);
        http.httpBasic(ServerHttpSecurity.HttpBasicSpec::disable);
        http.formLogin(ServerHttpSecurity.FormLoginSpec::disable);
        http.logout(ServerHttpSecurity.LogoutSpec::disable);
        return http.build();
    }

    /**
     * 会话链：浏览器登录、令牌转发、CSRF 与注销。
     *
     * @param http              Spring Security HTTP 配置
     * @param clients           客户端注册（Keycloak）
     * @param authorizedClients 已授权客户端仓库（存在会话里）
     * @param entryPoint        401 JSON
     * @param denied            403 JSON
     * @return 过滤链
     */
    @Bean
    @Order(2)
    SecurityWebFilterChain gatewaySecurityFilterChain(ServerHttpSecurity http, ReactiveClientRegistrationRepository clients,
                                                      ServerOAuth2AuthorizedClientRepository authorizedClients,
                                                      ApiAuthenticationEntryPoint entryPoint, ApiAccessDeniedHandler denied) {
        DefaultServerOAuth2AuthorizationRequestResolver authorizationRequests = new DefaultServerOAuth2AuthorizationRequestResolver(clients);
        // 机密客户端也用 PKCE（RFC 9700）；Keycloak 侧 github-gateway 要求 S256。
        authorizationRequests.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        OidcClientInitiatedServerLogoutSuccessHandler logoutSuccess = new OidcClientInitiatedServerLogoutSuccessHandler(clients);
        // 回到发起注销的入口（经 admin-web 开发代理时是 8000，直连网关时是 7100；两者都已登记在 Keycloak）。
        logoutSuccess.setPostLogoutRedirectUri("{baseUrl}/");
        logoutSuccess.setLogoutSuccessUrl(URI.create("/"));
        logoutSuccess.setRedirectStrategy(new SpaLogoutRedirectStrategy());
        CookieServerCsrfTokenRepository csrfTokens = CookieServerCsrfTokenRepository.withHttpOnlyFalse();
        csrfTokens.setCookiePath("/");
        csrfTokens.setCookieCustomizer(cookie -> cookie.secure(true).sameSite("Lax"));
        http.authorizeExchange(exchanges -> exchanges
                .matchers(EndpointRequest.toAnyEndpoint()).permitAll()
                .pathMatchers(API_DOCS).permitAll()
                .anyExchange().authenticated());
        http.oauth2Login(login -> login.authorizationRequestResolver(authorizationRequests).authorizedClientRepository(authorizedClients));
        http.exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(loginOrUnauthorized(entryPoint)).accessDeniedHandler(denied));
        http.csrf(csrf -> csrf.csrfTokenRepository(csrfTokens).csrfTokenRequestHandler(new SpaServerCsrfTokenRequestHandler())
                .accessDeniedHandler(denied));
        http.addFilterAfter(new CsrfCookieWebFilter(), SecurityWebFiltersOrder.CSRF);
        // 注销时整个会话作废（连同会话里的访问令牌与刷新令牌），再跳到 Keycloak 结束 SSO 会话。
        http.logout(logout -> logout.logoutHandler(handlers -> handlers.add(new WebSessionServerLogoutHandler()))
                .logoutSuccessHandler(logoutSuccess));
        http.httpBasic(ServerHttpSecurity.HttpBasicSpec::disable);
        http.formLogin(ServerHttpSecurity.FormLoginSpec::disable);
        return http.build();
    }

    /**
     * 已授权客户端存在会话里（随 Spring Session 进 Redis），网关重启或多实例时登录态与令牌一起保留；缺省的内存实现会在重启后丢令牌。
     *
     * @return 已授权客户端仓库
     */
    @Bean
    ServerOAuth2AuthorizedClientRepository authorizedClientRepository() {
        return new WebSessionServerOAuth2AuthorizedClientRepository();
    }

    /**
     * TokenRelay 用的授权客户端管理器：会话里的访问令牌到期（含缺省 60 秒时钟偏差）时用刷新令牌续期。
     * github-dev 域不轮换刷新令牌，并发续期互不影响。
     *
     * @param clients           客户端注册
     * @param authorizedClients 已授权客户端仓库
     * @return 管理器
     */
    @Bean
    ReactiveOAuth2AuthorizedClientManager authorizedClientManager(ReactiveClientRegistrationRepository clients,
                                                                  ServerOAuth2AuthorizedClientRepository authorizedClients) {
        DefaultReactiveOAuth2AuthorizedClientManager manager = new DefaultReactiveOAuth2AuthorizedClientManager(clients, authorizedClients);
        manager.setAuthorizedClientProvider(ReactiveOAuth2AuthorizedClientProviderBuilder.builder().authorizationCode().refreshToken().build());
        return manager;
    }

    /**
     * 会话 Cookie：名称 {@value #SESSION_COOKIE}，Secure、HttpOnly、SameSite=Lax（Strict 会让 Keycloak 回调丢会话）、Path=/。
     *
     * @return 会话 ID 解析器
     */
    @Bean
    WebSessionIdResolver webSessionIdResolver() {
        CookieWebSessionIdResolver resolver = new CookieWebSessionIdResolver();
        resolver.setCookieName(SESSION_COOKIE);
        resolver.addCookieInitializer(cookie -> cookie.path("/").secure(true).httpOnly(true).sameSite("Lax"));
        return resolver;
    }

    /**
     * 让 {@code spring.security.oauth2.resourceserver.jwt.jws-algorithms} 在 issuer-uri 方式下也生效（Spring Boot 只在
     * jwk-set-uri 方式应用，issuer 方式会按公钥集推断算法）；与 utils 的 servlet 资源服务器一致。
     *
     * @param properties Spring Boot 资源服务器配置
     * @return 解码器构建定制
     */
    @Bean
    JwkSetUriReactiveJwtDecoderBuilderCustomizer gatewayJwsAlgorithmsCustomizer(OAuth2ResourceServerProperties properties) {
        return builder -> builder.jwsAlgorithms(algorithms -> properties.getJwt().getJwsAlgorithms().forEach(name -> {
            SignatureAlgorithm algorithm = SignatureAlgorithm.from(name);
            if (algorithm == null) {
                throw new IllegalStateException("spring.security.oauth2.resourceserver.jwt.jws-algorithms 含未知算法：" + name);
            }
            algorithms.add(algorithm);
        }));
    }

    /**
     * 401 JSON。
     *
     * @return 入口
     */
    @Bean
    ApiAuthenticationEntryPoint apiAuthenticationEntryPoint() {
        return new ApiAuthenticationEntryPoint();
    }

    /**
     * 403 JSON。
     *
     * @return 处理器
     */
    @Bean
    ApiAccessDeniedHandler apiAccessDeniedHandler() {
        return new ApiAccessDeniedHandler();
    }

    /**
     * 未登录：浏览器页面跳转（Accept 含 text/html 且不是 XHR）去登录，其余（API、XHR）返回 401 JSON。
     */
    private static DelegatingServerAuthenticationEntryPoint loginOrUnauthorized(ApiAuthenticationEntryPoint api) {
        MediaTypeServerWebExchangeMatcher html = new MediaTypeServerWebExchangeMatcher(MediaType.TEXT_HTML);
        html.setIgnoredMediaTypes(Set.of(MediaType.ALL));
        ServerWebExchangeMatcher xhr = exchange -> "XMLHttpRequest".equalsIgnoreCase(exchange.getRequest().getHeaders().getFirst("X-Requested-With"))
                ? ServerWebExchangeMatcher.MatchResult.match() : ServerWebExchangeMatcher.MatchResult.notMatch();
        DelegatingServerAuthenticationEntryPoint entryPoint = new DelegatingServerAuthenticationEntryPoint(
                new DelegatingServerAuthenticationEntryPoint.DelegateEntry(new AndServerWebExchangeMatcher(html, new NegatedServerWebExchangeMatcher(xhr)),
                        new RedirectServerAuthenticationEntryPoint(LOGIN_ENTRY)));
        entryPoint.setDefaultEntryPoint(api);
        return entryPoint;
    }

    private static Mono<ServerWebExchangeMatcher.MatchResult> bearerRequest(ServerWebExchange exchange) {
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        return authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                ? ServerWebExchangeMatcher.MatchResult.match() : ServerWebExchangeMatcher.MatchResult.notMatch();
    }
}

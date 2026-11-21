package com.kellen.config.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.actuate.web.reactive.EndpointRequest;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientPropertiesMapper;
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
import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.OAuth2RefreshTokenGrantRequest;
import org.springframework.security.oauth2.client.endpoint.ReactiveOAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.WebClientReactiveAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.WebClientReactiveRefreshTokenTokenResponseClient;
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationFailureHandler;
import org.springframework.security.web.server.authentication.logout.ServerLogoutSuccessHandler;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.oidc.web.server.logout.OidcClientInitiatedServerLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.server.DefaultServerOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.server.WebSessionServerOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.DefaultReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.DelegatingServerAuthenticationEntryPoint;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authentication.ServerAuthenticationEntryPointFailureHandler;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Set;

/**
 * 网关 BFF 的安全配置（Spring Security WebFlux，按请求的凭证分两条过滤链，做法同 Spring Security 文档的多过滤链）：
 *
 * <ul>
 *     <li>Bearer 链：请求带 {@code Authorization: Bearer} 时按 Keycloak 访问令牌校验（签发方、受众、有效期、只收 RS256），
 *     无状态、不建会话、不要 CSRF，通过后原样转发，细粒度授权在各服务。</li>
 *     <li>会话链：其余请求。浏览器经 Keycloak 授权码加 PKCE 登录，会话存 Spring Session（Redis），已授权客户端（访问令牌与
 *     刷新令牌）存在会话里；转发时 TokenRelay 换成 Bearer 头，到期由授权客户端管理器用刷新令牌续期。写请求要带
 *     {@code X-XSRF-TOKEN}（值取自 {@code XSRF-TOKEN} Cookie，Spring Security 单页应用写法，只认请求头）；{@code POST /logout}
 *     结束网关会话并做 Keycloak 的 RP 发起注销。登录回调失败跳到 {@value #LOGIN_FAILURE}（admin-web 的登录失败页），该页不经过登录。</li>
 * </ul>
 *
 * <p>Keycloak 登录注册在第一次用到时才按 issuer-uri 发现（{@link SupplierReactiveClientRegistrationRepository}），Bearer 校验的公钥集
 * 同样延迟取得：Keycloak 不可达时网关照常启动，登录入口回 503 JSON，恢复后无需重启。</p>
 *
 * <p>Actuator 端点由更前面的 {@link com.kellen.config.SensitiveEndpointBlockFilter} 把关（健康检查公开，其余只走管理端口并
 * 校验运维令牌），OpenAPI 文档是否对外也由它决定，这里都放行。未登录时页面跳转去登录，API 与 XHR 请求返回 401 JSON。</p>
 *
 * @author 孙凯伦
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
@EnableConfigurationProperties(OAuth2ClientProperties.class)
public class GatewaySecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(GatewaySecurityConfig.class);

    /**
     * Keycloak 登录注册（Nacos {@code spring.security.oauth2.client.registration.keycloak}）。
     */
    static final String REGISTRATION_ID = "keycloak";

    /**
     * 登录入口：跳到 Keycloak 的授权端点。
     */
    static final String LOGIN_ENTRY = "/oauth2/authorization/" + REGISTRATION_ID;

    /**
     * 登录回调失败时的去处：admin-web 的登录失败页（与前端的约定；Spring Security 的缺省值也是它，这里写明，免得缺省值变了前端失配）。
     */
    static final String LOGIN_FAILURE = "/login?error";

    /**
     * 登录失败页的路径：不经过登录，否则回调一直失败时浏览器会在网关与 Keycloak 之间整页循环。
     */
    private static final String LOGIN_PAGE = "/login";

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
     * @param jwtDecoder Spring Boot 按 issuer-uri 创建的解码器（包一层失败即关闭）
     * @return 过滤链
     */
    @Bean
    @Order(1)
    SecurityWebFilterChain gatewayBearerFilterChain(ServerHttpSecurity http, ApiAuthenticationEntryPoint entryPoint, ApiAccessDeniedHandler denied,
                                                    ReactiveJwtDecoder jwtDecoder) {
        http.securityMatcher(GatewaySecurityConfig::bearerRequest);
        http.authorizeExchange(exchanges -> exchanges
                .matchers(EndpointRequest.toAnyEndpoint()).permitAll()
                .pathMatchers(API_DOCS).permitAll()
                .anyExchange().authenticated());
        // 公钥集取不到等认证服务异常缺省会被重新抛出成 500 并打印堆栈；这里与 utils 的 servlet 资源服务器一致，交给 401 JSON。
        ServerAuthenticationEntryPointFailureHandler bearerFailure = new ServerAuthenticationEntryPointFailureHandler(entryPoint);
        bearerFailure.setRethrowAuthenticationServiceException(false);
        http.oauth2ResourceServer(resourceServer -> resourceServer.jwt(jwt -> jwt.jwtDecoder(new FailClosedReactiveJwtDecoder(jwtDecoder)))
                .authenticationEntryPoint(entryPoint).accessDeniedHandler(denied).authenticationFailureHandler(bearerFailure));
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
        ServerLogoutSuccessHandler logoutSuccess = logoutSuccessHandler(clients);
        CookieServerCsrfTokenRepository csrfTokens = CookieServerCsrfTokenRepository.withHttpOnlyFalse();
        csrfTokens.setCookiePath("/");
        csrfTokens.setCookieCustomizer(cookie -> cookie.secure(true).sameSite("Lax"));
        http.authorizeExchange(exchanges -> exchanges
                .matchers(EndpointRequest.toAnyEndpoint()).permitAll()
                .pathMatchers(API_DOCS).permitAll()
                .pathMatchers(LOGIN_PAGE).permitAll()
                .anyExchange().authenticated());
        http.oauth2Login(login -> login.authorizationRequestResolver(authorizationRequests).authorizedClientRepository(authorizedClients)
                .authenticationFailureHandler(new RedirectServerAuthenticationFailureHandler(LOGIN_FAILURE)));
        http.exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(loginOrUnauthorized(entryPoint)).accessDeniedHandler(denied));
        http.csrf(csrf -> csrf.csrfTokenRepository(csrfTokens).csrfTokenRequestHandler(new SpaServerCsrfTokenRequestHandler())
                .accessDeniedHandler(denied));
        http.addFilterAfter(new CsrfCookieWebFilter(), SecurityWebFiltersOrder.CSRF);
        // Keycloak 不可达、登录注册还没取到时回 503 JSON（登录入口、登录回调与 TokenRelay 都经过这条链）。
        http.addFilterAt(new ClientRegistrationUnavailableWebFilter(), SecurityWebFiltersOrder.FIRST);
        // 注销时整个会话作废（连同会话里的访问令牌与刷新令牌），再跳到 Keycloak 结束 SSO 会话。
        http.logout(logout -> logout.logoutHandler(handlers -> handlers.add(new WebSessionServerLogoutHandler()))
                .logoutSuccessHandler(logoutSuccess));
        http.httpBasic(ServerHttpSecurity.HttpBasicSpec::disable);
        http.formLogin(ServerHttpSecurity.FormLoginSpec::disable);
        return http.build();
    }

    /**
     * 注销成功后的跳转：RP 发起注销（OIDC RP-Initiated Logout 1.0），回到发起注销的入口。网关会话此前已作废；
     * 这时 Keycloak 不可达（网关重启后还没发现过 Keycloak）就退回本地首页，不把注销报成 503，Keycloak 的 SSO 会话等恢复后再注销。
     *
     * @param clients 客户端注册
     * @return 注销成功处理器
     */
    static ServerLogoutSuccessHandler logoutSuccessHandler(ReactiveClientRegistrationRepository clients) {
        SpaLogoutRedirectStrategy redirect = new SpaLogoutRedirectStrategy();
        OidcClientInitiatedServerLogoutSuccessHandler logoutSuccess = new OidcClientInitiatedServerLogoutSuccessHandler(clients);
        // 回到发起注销的入口（经 admin-web 开发代理时是 8000，直连网关时是 7100；两者都已登记在 Keycloak）。
        logoutSuccess.setPostLogoutRedirectUri("{baseUrl}/");
        logoutSuccess.setLogoutSuccessUrl(URI.create("/"));
        logoutSuccess.setRedirectStrategy(redirect);
        return (exchange, authentication) -> logoutSuccess.onLogoutSuccess(exchange, authentication)
                .onErrorResume(ClientRegistrationUnavailableException.class, failure -> {
                    log.warn("注销时 Keycloak 暂不可达，只结束了网关会话，回到本地首页");
                    return redirect.sendRedirect(exchange.getExchange(), URI.create("/"));
                });
    }

    /**
     * Keycloak 登录注册：第一次用到时才按 issuer-uri 发现（{@link SupplierReactiveClientRegistrationRepository}）。
     * Spring Boot 缺省在启动时发现，Keycloak 不可达网关就起不来；有了这个 Bean，Boot 的同名配置整体退让，
     * 所以这里自己启用 {@code spring.security.oauth2.client.*} 属性。注册内容仍由 Boot 的属性映射给出，与缺省一致。
     *
     * @param properties Spring Boot 的 OAuth2 客户端配置
     * @return 客户端注册仓库
     */
    @Bean
    ReactiveClientRegistrationRepository clientRegistrationRepository(OAuth2ClientProperties properties) {
        return new SupplierReactiveClientRegistrationRepository(() -> new InMemoryReactiveClientRegistrationRepository(
                new ArrayList<>(new OAuth2ClientPropertiesMapper(properties).asClientRegistrations().values())));
    }

    /**
     * Bearer 链的签发时间校验：iat 必须有，且不能晚于当前时间 60 秒以上（Spring Boot 按 issuer-uri 建解码器时装上容器里的校验器）。
     *
     * @return 令牌校验器
     */
    @Bean
    OAuth2TokenValidator<Jwt> gatewayIssuedAtValidator() {
        return new IssuedAtValidator();
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
                                                                  ServerOAuth2AuthorizedClientRepository authorizedClients,
                                                                  ReactiveOAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> refreshTokenTokenResponseClient) {
        DefaultReactiveOAuth2AuthorizedClientManager manager = new DefaultReactiveOAuth2AuthorizedClientManager(clients, authorizedClients);
        manager.setAuthorizedClientProvider(ReactiveOAuth2AuthorizedClientProviderBuilder.builder().authorizationCode()
                .refreshToken(refresh -> refresh.accessTokenResponseClient(refreshTokenTokenResponseClient)).build());
        return manager;
    }

    /**
     * 登录回调用授权码换令牌的客户端（Spring Security 按类型取用这个 Bean）：访问 Keycloak 令牌端点有连接与响应超时。
     *
     * @param connectTimeout  连接超时，缺省 5 秒
     * @param responseTimeout 响应超时，缺省 10 秒
     * @return 令牌请求客户端
     */
    @Bean
    ReactiveOAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> authorizationCodeTokenResponseClient(
            @Value("${gateway.oauth2.token-endpoint.connect-timeout:5s}") Duration connectTimeout,
            @Value("${gateway.oauth2.token-endpoint.response-timeout:10s}") Duration responseTimeout) {
        WebClientReactiveAuthorizationCodeTokenResponseClient client = new WebClientReactiveAuthorizationCodeTokenResponseClient();
        client.setWebClient(tokenEndpointWebClient(connectTimeout, responseTimeout));
        return client;
    }

    /**
     * TokenRelay 续期用刷新令牌换令牌的客户端：访问 Keycloak 令牌端点有连接与响应超时。
     *
     * @param connectTimeout  连接超时，缺省 5 秒
     * @param responseTimeout 响应超时，缺省 10 秒
     * @return 令牌请求客户端
     */
    @Bean
    ReactiveOAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> refreshTokenTokenResponseClient(
            @Value("${gateway.oauth2.token-endpoint.connect-timeout:5s}") Duration connectTimeout,
            @Value("${gateway.oauth2.token-endpoint.response-timeout:10s}") Duration responseTimeout) {
        WebClientReactiveRefreshTokenTokenResponseClient client = new WebClientReactiveRefreshTokenTokenResponseClient();
        client.setWebClient(tokenEndpointWebClient(connectTimeout, responseTimeout));
        return client;
    }

    /**
     * 访问 Keycloak 令牌端点的 WebClient：Spring Security 缺省的 WebClient 不限响应时间，Keycloak 卡住时登录与续期会一直挂着、占住连接；
     * 这里与 utils 出站取令牌一致（连接 5 秒、响应 10 秒），超时即失败、不转发请求。
     */
    private static WebClient tokenEndpointWebClient(Duration connectTimeout, Duration responseTimeout) {
        HttpClient http = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(connectTimeout.toMillis()))
                .responseTimeout(responseTimeout);
        return WebClient.builder().clientConnector(new ReactorClientHttpConnector(http)).build();
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
     * TokenRelay 续期失败的出口：刷新令牌失效时作废会话并回 401 JSON 或跳登录，连不上 Keycloak 时回 503 JSON。
     *
     * @return 全局过滤器
     */
    @Bean
    TokenRelayFailureFilter tokenRelayFailureFilter() {
        return new TokenRelayFailureFilter();
    }

    /**
     * 未登录：浏览器页面跳转（Accept 含 text/html 且不是 XHR）去登录，其余（API、XHR）返回 401 JSON。
     */
    private static DelegatingServerAuthenticationEntryPoint loginOrUnauthorized(ApiAuthenticationEntryPoint api) {
        DelegatingServerAuthenticationEntryPoint entryPoint = new DelegatingServerAuthenticationEntryPoint(
                new DelegatingServerAuthenticationEntryPoint.DelegateEntry(browserNavigationMatcher(), new RedirectServerAuthenticationEntryPoint(LOGIN_ENTRY)));
        entryPoint.setDefaultEntryPoint(api);
        return entryPoint;
    }

    /**
     * 是否为浏览器整页导航：Accept 含 text/html（不把 *&#47;* 算进去）且不是 XHR；登录入口跳转与续期失败的出口共用这一口径。
     *
     * @param exchange 当前请求
     * @return 是否整页导航
     */
    static Mono<Boolean> browserNavigation(ServerWebExchange exchange) {
        return browserNavigationMatcher().matches(exchange).map(ServerWebExchangeMatcher.MatchResult::isMatch);
    }

    private static ServerWebExchangeMatcher browserNavigationMatcher() {
        MediaTypeServerWebExchangeMatcher html = new MediaTypeServerWebExchangeMatcher(MediaType.TEXT_HTML);
        html.setIgnoredMediaTypes(Set.of(MediaType.ALL));
        ServerWebExchangeMatcher xhr = exchange -> "XMLHttpRequest".equalsIgnoreCase(exchange.getRequest().getHeaders().getFirst("X-Requested-With"))
                ? ServerWebExchangeMatcher.MatchResult.match() : ServerWebExchangeMatcher.MatchResult.notMatch();
        return new AndServerWebExchangeMatcher(html, new NegatedServerWebExchangeMatcher(xhr));
    }

    private static Mono<ServerWebExchangeMatcher.MatchResult> bearerRequest(ServerWebExchange exchange) {
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        return authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                ? ServerWebExchangeMatcher.MatchResult.match() : ServerWebExchangeMatcher.MatchResult.notMatch();
    }
}

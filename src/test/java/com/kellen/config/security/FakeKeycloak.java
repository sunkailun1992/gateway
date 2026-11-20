package com.kellen.config.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.net.InetAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 测试用的假 Keycloak（github-dev 域）：MockWebServer 提供发现文档、JWKS、令牌端点与用户信息端点，令牌用测试 RSA 密钥签名。
 * 授权端点与注销端点由浏览器访问，网关不调用，测试只核对网关给出的跳转地址。整个测试 JVM 共用一个实例。
 *
 * <p>令牌端点：授权码只认 {@link #CODE}，必须带 PKCE 的 code_verifier 与 client_secret_basic 认证；刷新令牌不轮换
 * （与 github-dev 域的 revoke_refresh_token=false 一致），同一个刷新令牌可以并发重复使用。</p>
 */
public final class FakeKeycloak {

    /** 网关在 github-dev 域的客户端。 */
    public static final String CLIENT_ID = "github-gateway";

    /** 测试客户端密钥（只存在于测试）。 */
    public static final String CLIENT_SECRET = "fake-gateway-client-secret";

    /** 假授权端点发给浏览器的授权码。 */
    public static final String CODE = "fixture-authorization-code";

    /** 发给登录会话的刷新令牌。 */
    public static final String REFRESH_TOKEN = "fixture-refresh-token";

    private static final String REALM = "/realms/github-dev";

    /** Bearer 访问令牌的签发与到期时刻固定，测试结果不依赖真实时间。 */
    private static final Instant BEARER_ISSUED = Instant.parse("2026-01-01T00:00:00Z");

    private static final Instant BEARER_EXPIRES = Instant.parse("2099-01-01T00:00:00Z");

    private static final FakeKeycloak INSTANCE = new FakeKeycloak();

    private final MockWebServer server = new MockWebServer();

    private final RSAKey key;

    private final List<Map<String, String>> tokenRequests = new CopyOnWriteArrayList<>();

    private final AtomicInteger accessTokens = new AtomicInteger();

    private final AtomicReference<String> nonce = new AtomicReference<>();

    private final AtomicReference<String> lastAccessToken = new AtomicReference<>();

    private volatile long accessTokenSeconds = 300;

    private FakeKeycloak() {
        try {
            key = new RSAKeyGenerator(2048).keyID("fake-keycloak-key").generate();
            server.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    return handle(request);
                }
            });
            server.start(InetAddress.getLoopbackAddress(), 0);
        } catch (Exception failure) {
            throw new IllegalStateException("假 Keycloak 启动失败", failure);
        }
    }

    /**
     * 共用实例。
     *
     * @return 假 Keycloak
     */
    public static FakeKeycloak get() {
        return INSTANCE;
    }

    /**
     * 把网关的 Keycloak 登录注册与 Bearer 校验指向假 Keycloak；键与 Nacos {@code gateway-spring.yaml} 一致。
     *
     * @param registry 动态属性
     */
    public static void register(DynamicPropertyRegistry registry) {
        FakeKeycloak keycloak = get();
        String registration = "spring.security.oauth2.client.registration.keycloak.";
        registry.add(registration + "provider", () -> "keycloak");
        registry.add(registration + "client-id", () -> CLIENT_ID);
        registry.add(registration + "client-secret", () -> CLIENT_SECRET);
        registry.add(registration + "authorization-grant-type", () -> "authorization_code");
        registry.add(registration + "scope", () -> "openid");
        registry.add("spring.security.oauth2.client.provider.keycloak.issuer-uri", keycloak::issuer);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", keycloak::issuer);
        registry.add("spring.security.oauth2.resourceserver.jwt.jws-algorithms", () -> "RS256");
        registry.add("spring.security.oauth2.resourceserver.jwt.audiences", () -> "github-api,github-mcp,github-ai-agent");
    }

    /**
     * 签发方地址。
     *
     * @return 签发方
     */
    public String issuer() {
        return "http://127.0.0.1:" + server.getPort() + REALM;
    }

    /**
     * 受众为 {@code github-api} 的有效 Bearer 访问令牌（脚本与工具直接调用时带的那种）。
     *
     * @return 令牌原文
     */
    public String bearerToken() {
        return bearerToken("github-api");
    }

    /**
     * 指定受众的有效 Bearer 访问令牌。
     *
     * @param audience 受众
     * @return 令牌原文
     */
    public String bearerToken(String audience) {
        return sign(bearerClaims(audience), JWSAlgorithm.RS256);
    }

    /**
     * 用同一把 RSA 密钥、但不是 RS256 的算法签的 Bearer 令牌（只收 RS256 时应拒绝）。
     *
     * @param algorithm 签名算法，如 RS512
     * @return 令牌原文
     */
    public String bearerTokenSignedWith(JWSAlgorithm algorithm) {
        return sign(bearerClaims("github-api"), algorithm);
    }

    private JWTClaimsSet bearerClaims(String audience) {
        return new JWTClaimsSet.Builder().issuer(issuer()).subject("kc-script-user").audience(audience)
                .claim("azp", CLIENT_ID).claim("user_id", "fixture-user-1")
                .issueTime(Date.from(BEARER_ISSUED)).expirationTime(Date.from(BEARER_EXPIRES)).build();
    }

    /**
     * 下一次授权码换令牌时 ID 令牌要回带的 nonce（取自网关给浏览器的授权跳转）。
     *
     * @param value 授权请求里的 nonce
     */
    public void expectNonce(String value) {
        nonce.set(value);
    }

    /**
     * 之后签发的访问令牌有效秒数；小于 60 秒时网关按已过期处理（授权客户端的时钟偏差缺省 60 秒），每次转发都会先刷新。
     *
     * @param seconds 有效秒数
     */
    public void accessTokenSeconds(long seconds) {
        accessTokenSeconds = seconds;
    }

    /**
     * 最近一次签发的访问令牌。
     *
     * @return 令牌原文
     */
    public String lastAccessToken() {
        return lastAccessToken.get();
    }

    /**
     * 令牌端点收到的请求（表单参数加 {@code authorization} 头）。
     *
     * @return 请求
     */
    public List<Map<String, String>> tokenRequests() {
        return tokenRequests;
    }

    /**
     * 清空记录并恢复缺省的访问令牌有效期。
     */
    public void reset() {
        tokenRequests.clear();
        nonce.set(null);
        accessTokenSeconds = 300;
    }

    private MockResponse handle(RecordedRequest request) {
        String path = request.getRequestUrl() == null ? "" : request.getRequestUrl().encodedPath();
        return switch (path) {
            case REALM + "/.well-known/openid-configuration" -> json(discovery());
            case REALM + "/protocol/openid-connect/certs" -> json(new JWKSet(key.toPublicJWK()).toString());
            case REALM + "/protocol/openid-connect/token" -> token(request);
            case REALM + "/protocol/openid-connect/userinfo" -> json("{\"sub\":\"kc-user-1\",\"preferred_username\":\"fixture-user\"}");
            default -> new MockResponse().setResponseCode(404);
        };
    }

    private String discovery() {
        return """
                {"issuer":"%1$s","authorization_endpoint":"%1$s/protocol/openid-connect/auth",
                 "token_endpoint":"%1$s/protocol/openid-connect/token","jwks_uri":"%1$s/protocol/openid-connect/certs",
                 "userinfo_endpoint":"%1$s/protocol/openid-connect/userinfo","end_session_endpoint":"%1$s/protocol/openid-connect/logout",
                 "response_types_supported":["code"],"subject_types_supported":["public"],
                 "id_token_signing_alg_values_supported":["RS256"],"code_challenge_methods_supported":["S256"],
                 "grant_types_supported":["authorization_code","refresh_token"],
                 "token_endpoint_auth_methods_supported":["client_secret_basic"],"scopes_supported":["openid","profile"]}
                """.formatted(issuer());
    }

    private MockResponse token(RecordedRequest request) {
        Map<String, String> form = form(request.getBody().readUtf8());
        form.put("authorization", String.valueOf(request.getHeader("Authorization")));
        tokenRequests.add(form);
        String basic = "Basic " + Base64.getEncoder().encodeToString((CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));
        if (!basic.equals(request.getHeader("Authorization"))) {
            return error(401, "invalid_client");
        }
        String grant = form.get("grant_type");
        if ("authorization_code".equals(grant) && CODE.equals(form.get("code")) && form.get("code_verifier") != null) {
            return tokens();
        }
        if ("refresh_token".equals(grant) && REFRESH_TOKEN.equals(form.get("refresh_token"))) {
            return tokens();
        }
        return error(400, "invalid_grant");
    }

    private MockResponse tokens() {
        String access = "fixture-access-token-" + accessTokens.incrementAndGet();
        lastAccessToken.set(access);
        return json("""
                {"access_token":"%s","token_type":"Bearer","expires_in":%d,"refresh_token":"%s","refresh_expires_in":1800,
                 "id_token":"%s","scope":"openid profile"}""".formatted(access, accessTokenSeconds, REFRESH_TOKEN, idToken()));
    }

    private String idToken() {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().issuer(issuer()).subject("kc-user-1").audience(CLIENT_ID)
                .claim("azp", CLIENT_ID).claim("preferred_username", "fixture-user").claim("sid", "fixture-session")
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(300)));
        if (nonce.get() != null) {
            claims.claim("nonce", nonce.get());
        }
        return sign(claims.build(), JWSAlgorithm.RS256);
    }

    private String sign(JWTClaimsSet claims, JWSAlgorithm algorithm) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(algorithm).keyID(key.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (Exception failure) {
            throw new IllegalStateException("签发测试令牌失败", failure);
        }
    }

    private static Map<String, String> form(String body) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int split = pair.indexOf('=');
            String name = URLDecoder.decode(split < 0 ? pair : pair.substring(0, split), StandardCharsets.UTF_8);
            String value = split < 0 ? "" : URLDecoder.decode(pair.substring(split + 1), StandardCharsets.UTF_8);
            values.put(name, value);
        }
        return values;
    }

    private static MockResponse json(String body) {
        return new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body);
    }

    private static MockResponse error(int status, String error) {
        return new MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"" + error + "\"}");
    }
}

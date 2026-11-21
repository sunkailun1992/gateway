package com.kellen.config.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Bearer 访问令牌的签发时间（{@code iat}）：必须有（RFC 9068 §2.2），且不能晚于当前时间超过 60 秒。
 *
 * <p>Spring Security 的 {@code JwtTimestampValidator} 只看 {@code exp} 与 {@code nbf}，Keycloak 不写 {@code nbf}；
 * {@code JwtIssuedAtValidator} 是给 DPoP 证明用的双向窗口（签发早于 60 秒也拒），不适用于访问令牌。口径与 utils 资源服务器的
 * {@code FleetIssuedAtValidator}、RAG 引擎（PyJWT）一致；网关不依赖 utils，这里各写一份。</p>
 *
 * @author 孙凯伦
 */
final class IssuedAtValidator implements OAuth2TokenValidator<Jwt> {

    private static final Duration CLOCK_SKEW = Duration.ofSeconds(60);

    private final Clock clock = Clock.systemUTC();

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        Instant issuedAt = jwt.getIssuedAt();
        if (issuedAt == null) {
            return invalid("iat claim is required.");
        }
        if (issuedAt.isAfter(Instant.now(clock).plus(CLOCK_SKEW))) {
            return invalid("iat claim is in the future.");
        }
        return OAuth2TokenValidatorResult.success();
    }

    private static OAuth2TokenValidatorResult invalid(String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, description,
                "https://tools.ietf.org/html/rfc6750#section-3.1"));
    }
}

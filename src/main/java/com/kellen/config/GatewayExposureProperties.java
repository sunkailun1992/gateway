package com.kellen.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 网关公网暴露面配置。
 *
 * <p>只控制 Swagger UI / OpenAPI 文档能否经网关访问；Actuator 拦截是固定规则，不提供开关。
 * 需要调整时写在对应 namespace 的 Nacos {@code gateway-spring.yaml}。</p>
 */
@Component
@ConfigurationProperties(prefix = "gateway.exposure")
public class GatewayExposureProperties {

    /**
     * 是否允许经网关访问 Swagger UI 与 OpenAPI 文档（含 {@code /{service}/v3/api-docs} 转发）。
     *
     * <p>不配置时按运行 profile 决定：prod 拦截，dev/test 放行。</p>
     */
    private Boolean apiDocsEnabled;

    public Boolean getApiDocsEnabled() {
        return apiDocsEnabled;
    }

    public void setApiDocsEnabled(Boolean apiDocsEnabled) {
        this.apiDocsEnabled = apiDocsEnabled;
    }
}

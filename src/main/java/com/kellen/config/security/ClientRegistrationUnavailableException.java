package com.kellen.config.security;

import java.io.Serial;

/**
 * 客户端注册暂时取不到：按 issuer-uri 取 Keycloak 发现文档失败（Keycloak 不可达、超时或返回的不是发现文档）。
 * 由 {@link ClientRegistrationUnavailableWebFilter} 转成 503 JSON；注销时退回本地首页。
 *
 * @author 孙凯伦
 */
class ClientRegistrationUnavailableException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 构造异常。
     *
     * @param message 说明
     * @param cause   发现失败的原因
     */
    ClientRegistrationUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

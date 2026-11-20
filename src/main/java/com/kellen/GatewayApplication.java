package com.kellen;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 网关服务启动类。
 *
 * <p>启动 Spring Cloud Gateway 并接入 Nacos。第5阶段起网关是 BFF：浏览器经 Keycloak 登录、会话存 Redis，转发时把会话里的
 * 访问令牌换成 Bearer 头（见 {@code com.kellen.config.security.GatewaySecurityConfig}）；业务鉴权、租户隔离和数据权限
 * 仍由后端业务服务处理，网关只拒绝未认证的请求。</p>
 */
@EnableDiscoveryClient
@SpringBootApplication
public class GatewayApplication {

    /**
     * JVM 进程入口，启动网关服务。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }

}

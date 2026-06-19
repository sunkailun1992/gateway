package com.kellen;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 网关服务启动类。
 *
 * <p>只负责启动 Spring Cloud Gateway 并注册到 Nacos；业务鉴权、租户隔离和数据权限
 * 仍由后端业务服务处理，避免网关承担业务语义。</p>
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

package com.kellen.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/** 为进程指标增加固定部署维度；业务会话、患者、租户与 trace ID 不能作为指标标签。 */
@Configuration(proxyBeanMethods = false)
public class FleetMetricsConfiguration {
    @Bean
    MeterRegistryCustomizer<MeterRegistry> fleetMetricsTags(ObjectProvider<BuildProperties> buildProperties,
            Environment environment) {
        String version = environment.getProperty("spring.application.version");
        if (!StringUtils.hasText(version)) {
            BuildProperties build = buildProperties.getIfAvailable();
            version = build == null ? null : build.getVersion();
        }
        // 原生 IDE 没有 build-info；显式启动版本与 OTel 对齐，缺失时不伪造构建版本。
        String metricsVersion = StringUtils.hasText(version) ? version : "unknown";
        return registry -> registry.config().commonTags("application", "gateway", "version", metricsVersion,
                "environment", environment.getProperty("observability.environment", "github-dev"));
    }
}

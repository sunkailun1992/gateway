package com.kellen.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证原生 IDE 不生成 build-info 时仍能装配指标，且版本维度与显式启动版本一致。 */
class FleetMetricsConfigurationTest {

    @Test
    void nativeIdeContextStartsWithoutBuildProperties() {
        assertTags("1.1.1", null, "github-dev", "1.1.1");
    }

    @Test
    void explicitVersionTakesPriorityOverPackagedBuildProperties() {
        assertTags("1.1.1", "1.1.0", "github-dev", "1.1.1");
    }

    @Test
    void packagedBuildVersionIsUsedWhenNoExplicitVersionExists() {
        assertTags(null, "1.1.0", "github-dev", "1.1.0");
    }

    @Test
    void blankExplicitVersionFallsBackToPackagedBuildVersion() {
        assertTags("  ", "1.1.0", "github-dev", "1.1.0");
    }

    @Test
    void missingVersionsRemainExplicitlyUnknown() {
        assertTags(null, null, "github-dev", "unknown");
    }

    @Test
    void blankBuildVersionIsUnknownAndEnvironmentCanBeOverridden() {
        assertTags(null, "  ", "test", "unknown");
    }

    private void assertTags(String explicitVersion, String buildVersion, String environmentName,
            String expectedVersion) {
        MockEnvironment environment = new MockEnvironment();
        if (explicitVersion != null) {
            environment.setProperty("spring.application.version", explicitVersion);
        }
        if (!"github-dev".equals(environmentName)) {
            environment.setProperty("observability.environment", environmentName);
        }
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.setEnvironment(environment);
            if (buildVersion != null) {
                Properties properties = new Properties();
                properties.setProperty("version", buildVersion);
                context.registerBean(BuildProperties.class, () -> new BuildProperties(properties));
            }
            context.register(FleetMetricsConfiguration.class);
            context.refresh();

            @SuppressWarnings("unchecked")
            MeterRegistryCustomizer<MeterRegistry> customizer = context.getBean(MeterRegistryCustomizer.class);
            customizer.customize(registry);

            assertThat(registry.counter("fleet.metrics.test").getId().getTag("application")).isEqualTo("gateway");
            assertThat(registry.counter("fleet.metrics.test").getId().getTag("version")).isEqualTo(expectedVersion);
            assertThat(registry.counter("fleet.metrics.test").getId().getTag("environment"))
                    .isEqualTo(environmentName);
        } finally {
            registry.close();
        }
    }
}

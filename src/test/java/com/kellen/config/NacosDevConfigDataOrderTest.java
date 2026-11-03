package com.kellen.config;

import com.alibaba.cloud.nacos.NacosConfigProperties;
import com.alibaba.cloud.nacos.configdata.NacosConfigDataLocationResolver;
import com.alibaba.cloud.nacos.configdata.NacosConfigDataResource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.config.ConfigDataLocation;
import org.springframework.boot.context.config.ConfigDataLocationResolverContext;
import org.springframework.boot.context.config.Profiles;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.env.StandardEnvironment;

import java.io.File;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用真实 Boot 默认搜索路径、操作系统环境和 Nacos 配置绑定验证 dev 私有文件的加载顺序。
 * 子进程只读取临时目录中的假配置；probe 协议不创建 Nacos 客户端或访问网络。
 */
class NacosDevConfigDataOrderTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void devPrivateFileIsVisibleBeforeNacosResolverBindsConnectionProperties() throws Exception {
        ProbeResult result = probe("dev", Map.of(), privateProperties("GITHUB_NACOS_DEV_SERVER_ADDR"));

        assertSuccessfulDev(result, "private-dev.invalid:8848");
        assertThat(result.output()).contains("RESOLVER_GROUP=PROBE_GROUP", "RESOLVER_USERNAME_PRESENT=true",
                "RESOLVER_PASSWORD_PRESENT=true", "PRIVATE_DEV_FILE_PRESENT=true");
    }

    @Test
    void sharedAddressVariableRemainsAnExplicitFallback() throws Exception {
        ProbeResult result = probe("dev", Map.of(), privateProperties("GITHUB_NACOS_SERVER_ADDR"));

        assertSuccessfulDev(result, "private-dev.invalid:8848");
    }

    @Test
    void operatingSystemEnvironmentOverridesPrivateDevProperties() throws Exception {
        ProbeResult result = probe("dev", Map.of("GITHUB_NACOS_DEV_SERVER_ADDR", "os-dev.invalid:8848"),
                privateProperties("GITHUB_NACOS_DEV_SERVER_ADDR"));

        assertSuccessfulDev(result, "os-dev.invalid:8848");
        assertThat(result.output()).doesNotContain("RESOLVER_ADDRESS=private-dev.invalid:8848");
    }

    @Test
    void additionalProfilesKeepTheDevPrivateFileAvailable() throws Exception {
        ProbeResult result = probe("dev,extra", Map.of(), privateProperties("GITHUB_NACOS_DEV_SERVER_ADDR"));

        assertSuccessfulDev(result, "private-dev.invalid:8848");
        assertThat(result.output()).contains("ACTIVE_PROFILES=dev,extra");
    }

    @Test
    void testAndProdDoNotReadTheDevPrivateFile() throws Exception {
        for (String profile : List.of("test", "prod")) {
            ProbeResult result = probe(profile,
                    Map.of("GITHUB_NACOS_" + profile.toUpperCase(java.util.Locale.ROOT) + "_SERVER_ADDR",
                            profile + ".invalid:8848"),
                    privateProperties("GITHUB_NACOS_DEV_SERVER_ADDR"));

            assertThat(result.exitCode()).as(result.output()).isZero();
            assertThat(result.output()).contains("RESOLVER_ADDRESS=" + profile + ".invalid:8848",
                    "RESOLVER_NAMESPACE=github-" + profile, "PRIVATE_DEV_FILE_PRESENT=false");
            assertThat(result.output()).doesNotContain("private-dev.invalid", "RESOLVER_GROUP=PROBE_GROUP");
        }
    }

    @Test
    void explicitSpringProfileOverridesTheAppEnvDefault() throws Exception {
        ProbeResult result = probe("dev", Map.of("SPRING_PROFILES_ACTIVE", "test",
                        "GITHUB_NACOS_TEST_SERVER_ADDR", "test.invalid:8848"),
                privateProperties("GITHUB_NACOS_DEV_SERVER_ADDR"));

        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output()).contains("ACTIVE_PROFILES=test", "RESOLVER_ADDRESS=test.invalid:8848",
                "PRIVATE_DEV_FILE_PRESENT=false");
    }

    @Test
    void missingAddressFailsRequiredResolutionWithoutLoopbackFallback() throws Exception {
        ProbeResult result = probe("dev", Map.of(), "GITHUB_NACOS_DEV_NAMESPACE=github-dev\n");

        assertThat(result.exitCode()).as(result.output()).isNotZero();
        assertThat(result.output()).contains("Could not resolve placeholder 'GITHUB_NACOS_SERVER_ADDR'");
        assertThat(result.output()).doesNotContain("RESOLVER_ADDRESS=127.0.0.1", "FINAL_ADDRESS=127.0.0.1",
                "CONFIGURATION_READY=true");
    }

    private void assertSuccessfulDev(ProbeResult result, String address) {
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output()).contains("RESOLVER_ADDRESS=" + address, "FINAL_ADDRESS=" + address,
                "RESOLVER_NAMESPACE=github-dev", "CONFIGURATION_READY=true");
    }

    /** 每次使用独立 cwd 和真实进程环境，避免依赖或修改测试机的私有连接配置。 */
    private ProbeResult probe(String appEnv, Map<String, String> environmentOverrides, String properties)
            throws Exception {
        Path scenario = Files.createTempDirectory(temporaryDirectory, "scenario-");
        Path classpath = Files.createDirectories(scenario.resolve("classpath"));
        Path workingDirectory = Files.createDirectories(scenario.resolve("working"));
        copyApplicationConfiguration(classpath, "application.yml");
        for (String profile : List.of("dev", "test", "prod")) {
            copyApplicationConfiguration(classpath, "application-" + profile + ".yml");
        }
        Path configDirectory = Files.createDirectories(workingDirectory.resolve("config"));
        Files.writeString(configDirectory.resolve("application-dev.properties"), properties);
        Path output = scenario.resolve("output.log");
        ProcessBuilder processBuilder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", childClasspath(classpath), ProbeMain.class.getName())
                .directory(workingDirectory.toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
        processBuilder.environment().clear();
        processBuilder.environment().put("APP_ENV", appEnv);
        processBuilder.environment().putAll(environmentOverrides);
        Process process = processBuilder.start();
        try {
            assertThat(process.waitFor(15, TimeUnit.SECONDS)).as("ConfigData 子进程必须及时结束").isTrue();
            return new ProbeResult(process.exitValue(), Files.readString(output));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    /** 使用本仓真实配置，只把远程 import 替换为不联网的可选 probe；不镜像连接占位符。 */
    private void copyApplicationConfiguration(Path directory, String filename) throws Exception {
        try (var input = getClass().getResourceAsStream("/" + filename)) {
            if (input == null) {
                throw new IllegalStateException("Missing application configuration: " + filename);
            }
            String configuration = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            configuration = configuration.replaceAll("(?<![\\w.-])(?:optional:)?nacos:(?=\\S)",
                    "optional:probe:");
            Files.writeString(directory.resolve(filename), configuration);
        }
    }

    /** Gradle 的测试依赖在独立 classloader 中，不能只传 worker 的 java.class.path。 */
    private String childClasspath(Path fixtureClasspath) throws Exception {
        LinkedHashSet<String> entries = new LinkedHashSet<>();
        entries.add(fixtureClasspath.toString());
        for (ClassLoader loader = getClass().getClassLoader(); loader != null; loader = loader.getParent()) {
            if (loader instanceof URLClassLoader urls) {
                for (java.net.URL url : urls.getURLs()) {
                    if ("file".equals(url.getProtocol())) {
                        entries.add(Path.of(url.toURI()).toAbsolutePath().toString());
                    }
                }
            }
        }
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            entries.add(Path.of(entry).toAbsolutePath().toString());
        }
        return String.join(File.pathSeparator, entries);
    }

    private String privateProperties(String addressKey) {
        return addressKey + "=private-dev.invalid:8848\n"
                + "GITHUB_NACOS_DEV_NAMESPACE=github-dev\n"
                + "GITHUB_NACOS_GROUP=PROBE_GROUP\n"
                + "GITHUB_NACOS_USERNAME=probe-user\n"
                + "GITHUB_NACOS_PASSWORD=probe-password\n";
    }

    private record ProbeResult(int exitCode, String output) {
    }

    /** 只执行 ConfigData 阶段，不创建 ApplicationContext、Nacos 客户端或 Web 服务。 */
    public static class ProbeMain {
        public static void main(String[] args) {
            StandardEnvironment environment = new StandardEnvironment();
            ConfigDataEnvironmentPostProcessor.applyTo(environment, null, null, List.of());
            assertResolverVisited();
            System.out.println("ACTIVE_PROFILES=" + String.join(",", environment.getActiveProfiles()));
            System.out.println("PRIVATE_DEV_FILE_PRESENT=" + environment.containsProperty("GITHUB_NACOS_USERNAME"));
            System.out.println("FINAL_ADDRESS=" + environment.getRequiredProperty("custom.nacos-ip"));
            System.out.println("CONFIGURATION_READY=true");
        }

        private static void assertResolverVisited() {
            if (!ProbeResolver.visited) {
                throw new IllegalStateException("Nacos ConfigData resolver probe was not visited");
            }
        }
    }

    /** 复用真实 Nacos 连接属性绑定；空远程资源列表保证本测试不访问外部服务。 */
    public static class ProbeResolver extends NacosConfigDataLocationResolver {
        private static boolean visited;

        public ProbeResolver(DeferredLogFactory logs) {
            super(logs);
        }

        @Override
        protected String getPrefix() {
            return "probe:";
        }

        @Override
        public List<NacosConfigDataResource> resolveProfileSpecific(ConfigDataLocationResolverContext context,
                ConfigDataLocation location, Profiles profiles) {
            NacosConfigProperties properties = loadProperties(context);
            visited = true;
            System.out.println("RESOLVER_ADDRESS=" + properties.getServerAddr());
            System.out.println("RESOLVER_NAMESPACE=" + properties.getNamespace());
            System.out.println("RESOLVER_GROUP=" + properties.getGroup());
            System.out.println("RESOLVER_USERNAME_PRESENT=" + !properties.getUsername().isEmpty());
            System.out.println("RESOLVER_PASSWORD_PRESENT=" + !properties.getPassword().isEmpty());
            return List.of();
        }
    }
}

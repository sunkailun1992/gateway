package com.kellen.config;

import com.kellen.fixture.GatewayClientTracingAgentProbe;
import com.kellen.fixture.SpanCaptureExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 真正 Gateway/自动 SERVER/SDK exporter/HTTP 断连合同，不调用模型或环境服务。 */
class GatewayClientTracingAgentTest {
    @TempDir Path temporary;

    @Test void normalBodyKeepsOneClientAndBothAutomaticServers() throws Exception { verify("normal", "success"); }
    @Test void activeCancelKeepsOneClientAndCancelsActualUpstream() throws Exception { verify("cancel", "cancelled"); }
    @Test void upstreamBodyFailureRemainsErrorAndKeepsParents() throws Exception { verify("error", "error"); }

    private void verify(String mode, String outcome) throws Exception {
        String agentPath = System.getenv("FLEET_TEST_AGENT");
        String privacyPath = System.getenv("FLEET_TEST_PRIVACY");
        assumeTrue(agentPath != null && privacyPath != null, "显式提供固定 agent/隐私扩展后执行隔离子 JVM 验收");
        Path agent = Path.of(agentPath), privacy = Path.of(privacyPath);
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(agent))))
                .isEqualTo("e43a270df53c6f423e0ab157be9c27ccc01d5dc3232e162fe54777573db2f149");
        Path extension = temporary.resolve("capture.jar"), capture = temporary.resolve("spans.tsv"), output = temporary.resolve("probe.log");
        try (var jar = new JarOutputStream(Files.newOutputStream(extension))) {
            for (String name : List.of("com/kellen/fixture/SpanCaptureExtension.class", "com/kellen/fixture/SpanCaptureExtension$Capture.class")) {
                jar.putNextEntry(new JarEntry(name));
                try (InputStream resource = SpanCaptureExtension.class.getClassLoader().getResourceAsStream(name)) {
                    assertThat(resource).isNotNull(); resource.transferTo(jar);
                }
                jar.closeEntry();
            }
            jar.putNextEntry(new JarEntry("META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider"));
            jar.write(SpanCaptureExtension.class.getName().getBytes(java.nio.charset.StandardCharsets.UTF_8)); jar.closeEntry();
        }
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx256m",
                "-javaagent:" + agent, "-Dotel.javaagent.extensions=" + privacy + "," + extension,
                "-Dfleet.test.capture=" + capture,
                "-cp", System.getProperty("fleet.test.classpath"), GatewayClientTracingAgentProbe.class.getName(), mode);
        process.environment().clear();
        process.environment().putAll(Map.of("OTEL_TRACES_EXPORTER", "logging", "OTEL_METRICS_EXPORTER", "none",
                "OTEL_LOGS_EXPORTER", "none", "OTEL_BSP_SCHEDULE_DELAY", "100", "OTEL_PROPAGATORS", "tracecontext",
                "OTEL_SERVICE_NAME", "isolated-gateway-client-fixture", "OTEL_INSTRUMENTATION_REACTOR_NETTY_1_0_ENABLED", "false"));
        Process child = process.directory(temporary.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertThat(child.waitFor(60, TimeUnit.SECONDS)).isTrue();
            String log = Files.readString(output);
            assertThat(child.exitValue()).as(log).isZero();
            assertThat(log).contains("EXIT", "CLIENT_CLOSED");
            if (mode.equals("cancel")) assertThat(log).contains("UPSTREAM_CANCEL=true", "CLIENT_READ=20");
            if (mode.equals("error")) assertThat(log).contains("CLIENT_ERROR=true");
            String[] gateway = match(log, "GATEWAY_SERVER=([0-9a-f]{32}):([0-9a-f]{16})");
            String[] upstream = match(log, "UPSTREAM_SERVER=([0-9a-f]{32}):([0-9a-f]{16})");
            String[] propagated = match(log, "UPSTREAM_PARENT=00-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}");
            List<String[]> spans = Files.readAllLines(capture).stream().map(line -> line.split("\t", -1)).toList();
            assertThat(spans).hasSize(4);
            assertThat(spans.stream().map(span -> span[0]).distinct().toList()).containsExactly(gateway[0]);
            assertThat(upstream[0]).isEqualTo(gateway[0]);
            assertThat(propagated[0]).isEqualTo(gateway[0]);
            String[] client = spans.stream().filter(span -> span[1].equals(propagated[1])).findFirst().orElseThrow();
            assertThat(client[3]).isEqualTo("CLIENT");
            assertThat(client[2]).isEqualTo(gateway[1]);
            assertThat(client[5]).isEqualTo(outcome);
            assertThat(client[4]).isEqualTo(mode.equals("error") ? "ERROR" : "UNSET");
            assertThat(spans.stream().filter(span -> span[3].equals("SERVER")).toList()).hasSize(2);
            assertThat(spans.stream().filter(span -> span[3].equals("CLIENT")).toList()).hasSize(2);
            String[] server = spans.stream().filter(span -> span[1].equals(upstream[1])).findFirst().orElseThrow();
            assertThat(server[2]).isEqualTo(client[1]);
            assertThat(spans.stream().map(span -> span[1]).distinct().count()).isEqualTo(4L);
            // 回执只有合成链路身份与固定合同结果，便于独立审查，不输出日志正文或任意 attributes。
            System.out.println("GATEWAY_TRACE_CONTRACT={\"case\":\"" + mode + "\",\"trace_id\":\"" + gateway[0]
                    + "\",\"gateway_server\":\"" + gateway[1] + "\",\"client_span\":\"" + client[1]
                    + "\",\"client_parent\":\"" + client[2] + "\",\"header_parent\":\"" + propagated[1]
                    + "\",\"upstream_server\":\"" + server[1] + "\",\"upstream_parent\":\"" + server[2]
                    + "\",\"outcome\":\"" + outcome + "\",\"span_count\":4,\"server_count\":2,\"gateway_client_count\":1,\"exit_code\":0}");
        } finally {
            if (child.isAlive()) { child.destroyForcibly(); child.waitFor(10, TimeUnit.SECONDS); }
        }
    }

    private static String[] match(String text, String expression) {
        var matcher = Pattern.compile(expression).matcher(text);
        assertThat(matcher.find()).as(expression).isTrue();
        return new String[]{matcher.group(1), matcher.group(2)};
    }
}

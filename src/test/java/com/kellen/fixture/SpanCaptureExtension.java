package com.kellen.fixture;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collection;

/** 子 JVM 测试专用：在 exporter 边界保存有限 span 身份/终态，不保存任意 attributes。 */
public final class SpanCaptureExtension implements AutoConfigurationCustomizerProvider {
    @Override
    public void customize(AutoConfigurationCustomizer customizer) {
        customizer.addSpanExporterCustomizer((delegate, config) -> new Capture(delegate));
    }

    static final class Capture implements SpanExporter {
        private final SpanExporter delegate;
        Capture(SpanExporter delegate) { this.delegate = delegate; }

        @Override
        public synchronized CompletableResultCode export(Collection<SpanData> spans) {
            try {
                Path path = Path.of(System.getProperty("fleet.test.capture"));
                for (SpanData span : spans) {
                    String outcome = span.getAttributes().get(AttributeKey.stringKey("business.outcome"));
                    String line = String.join("\t", span.getTraceId(), span.getSpanId(), span.getParentSpanId(),
                            span.getKind().name(), span.getStatus().getStatusCode().name(), outcome == null ? "" : outcome) + "\n";
                    Files.writeString(path, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                }
            } catch (Exception error) { return CompletableResultCode.ofFailure(); }
            return delegate.export(spans);
        }

        @Override public CompletableResultCode flush() { return delegate.flush(); }
        @Override public CompletableResultCode shutdown() { return delegate.shutdown(); }
    }
}

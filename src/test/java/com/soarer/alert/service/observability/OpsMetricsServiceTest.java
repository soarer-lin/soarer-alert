package com.soarer.alert.service.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpsMetricsServiceTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final OpsMetricsService service = new OpsMetricsService(registry);

    @Test
    void recordsModelCallDurationAndOutcome() {
        long started = service.startClock();

        service.recordModelCall(started, "qwen3.7-flash", "success");

        assertThat(registry.get("soarer.aiops.model.calls")
                .tag("model", "qwen3.7-flash")
                .tag("outcome", "success")
                .counter()
                .count()).isEqualTo(1.0);
        assertThat(registry.get("soarer.aiops.model.call.duration")
                .tag("model", "qwen3.7-flash")
                .timer()
                .count()).isEqualTo(1L);
    }

    @Test
    void sanitizesHighCardinalityTagValues() {
        assertThat(OpsMetricsService.safeTag(null)).isEqualTo("unknown");
        assertThat(OpsMetricsService.safeTag(" a\nb ")).isEqualTo("a_b");
        assertThat(OpsMetricsService.safeTag("x".repeat(100))).hasSize(80);
    }
}

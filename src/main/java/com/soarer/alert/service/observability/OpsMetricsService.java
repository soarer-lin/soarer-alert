package com.soarer.alert.service.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 运维指标服务。
 */
@Service
public class OpsMetricsService {

    private static final int MAX_TAG_LENGTH = 80;

    private final MeterRegistry meterRegistry;

    public OpsMetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public long startClock() {
        return System.nanoTime();
    }

    public void recordModelCall(long startedNanos, String model, String outcome) {
        recordTimer(
                "soarer.aiops.model.call.duration",
                "Duration of one OpenAI-compatible chat-model call.",
                startedNanos,
                "model",
                model,
                "outcome",
                outcome
        );
        incrementCounter(
                "soarer.aiops.model.calls",
                "Chat-model call outcomes.",
                "model",
                model,
                "outcome",
                outcome
        );
    }

    public void recordAgentInvocation(long startedNanos, String agentName, String outcome) {
        recordTimer(
                "soarer.aiops.agent.invocation.duration",
                "Duration of one Agent invocation.",
                startedNanos,
                "agent",
                agentName,
                "outcome",
                outcome
        );
        incrementCounter(
                "soarer.aiops.agent.invocations",
                "Agent invocation outcomes.",
                "agent",
                agentName,
                "outcome",
                outcome
        );
    }

    public void recordToolInvocation(long startedNanos, String toolName, String outcome) {
        recordTimer(
                "soarer.aiops.tool.call.duration",
                "Duration of one Agent tool invocation.",
                startedNanos,
                "tool",
                toolName,
                "outcome",
                outcome
        );
        incrementCounter(
                "soarer.aiops.tool.calls",
                "Agent tool invocation outcomes.",
                "tool",
                toolName,
                "outcome",
                outcome
        );
    }

    public void recordDiagnosisOutcome(String outcome, int attempt) {
        incrementCounter(
                "soarer.aiops.diagnosis.runs",
                "Diagnosis run processing outcomes.",
                "outcome",
                outcome,
                "attempt",
                Integer.toString(attempt)
        );
    }

    public void recordDocumentIndexOutcome(String outcome, int attempt) {
        incrementCounter(
                "soarer.aiops.document.index.runs",
                "Document indexing outcomes.",
                "outcome",
                outcome,
                "attempt",
                Integer.toString(attempt)
        );
    }

    public void recordStreamRetry(String stream, String reasonType) {
        incrementCounter(
                "soarer.aiops.stream.retries",
                "Redis Stream retry attempts.",
                "stream",
                stream,
                "reason",
                reasonType
        );
    }

    private void recordTimer(
            String name,
            String description,
            long startedNanos,
            String... tags
    ) {
        Timer.builder(name)
                .description(description)
                .tags(tags)
                .publishPercentileHistogram(true)
                .register(meterRegistry)
                .record(Duration.ofNanos(System.nanoTime() - startedNanos));
    }

    private void incrementCounter(String name, String description, String... tags) {
        Counter.builder(name)
                .description(description)
                .tags(tags)
                .register(meterRegistry)
                .increment();
    }

    public static String safeTag(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String normalized = value.strip().replace('\n', '_').replace('\r', '_');
        return normalized.length() <= MAX_TAG_LENGTH
                ? normalized
                : normalized.substring(0, MAX_TAG_LENGTH);
    }
}

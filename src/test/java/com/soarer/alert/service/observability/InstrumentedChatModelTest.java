package com.soarer.alert.service.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证 InstrumentedChatModel 的行为。
 */
class InstrumentedChatModelTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final OpsMetricsService metricsService = new OpsMetricsService(registry);
    private final ChatModel delegate = mock(ChatModel.class);
    private final Prompt prompt = mock(Prompt.class);
    private final InstrumentedChatModel model =
            new InstrumentedChatModel(delegate, metricsService, "qwen3.7-flash");

    @Test
    void recordsSuccessfulCall() {
        ChatResponse response = mock(ChatResponse.class);
        when(delegate.call(prompt)).thenReturn(response);

        ChatResponse result = model.call(prompt);

        assertThat(result).isSameAs(response);
        assertThat(registry.get("soarer.aiops.model.calls")
                .tag("outcome", "success")
                .counter()
                .count()).isEqualTo(1.0);
    }

    @Test
    void recordsFailedCallAndRethrows() {
        when(delegate.call(prompt)).thenThrow(new IllegalStateException("model unavailable"));

        assertThatThrownBy(() -> model.call(prompt))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("model unavailable");
        assertThat(registry.get("soarer.aiops.model.calls")
                .tag("outcome", "error")
                .counter()
                .count()).isEqualTo(1.0);
    }
}

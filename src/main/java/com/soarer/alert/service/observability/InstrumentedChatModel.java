package com.soarer.alert.service.observability;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.SignalType;
import reactor.core.publisher.Flux;

public final class InstrumentedChatModel implements ChatModel {

    private final ChatModel delegate;
    private final OpsMetricsService metricsService;
    private final String modelName;

    public InstrumentedChatModel(ChatModel delegate, OpsMetricsService metricsService, String modelName) {
        this.delegate = delegate;
        this.metricsService = metricsService;
        this.modelName = OpsMetricsService.safeTag(modelName);
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        long started = metricsService.startClock();
        try {
            ChatResponse response = delegate.call(prompt);
            metricsService.recordModelCall(started, modelName, "success");
            return response;
        } catch (RuntimeException | Error e) {
            metricsService.recordModelCall(started, modelName, "error");
            throw e;
        }
    }

    @Override
    public String call(String message) {
        long started = metricsService.startClock();
        try {
            String response = delegate.call(message);
            metricsService.recordModelCall(started, modelName, "success");
            return response;
        } catch (RuntimeException | Error e) {
            metricsService.recordModelCall(started, modelName, "error");
            throw e;
        }
    }

    @Override
    public String call(Message... messages) {
        long started = metricsService.startClock();
        try {
            String response = delegate.call(messages);
            metricsService.recordModelCall(started, modelName, "success");
            return response;
        } catch (RuntimeException | Error e) {
            metricsService.recordModelCall(started, modelName, "error");
            throw e;
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        long started = metricsService.startClock();
        return delegate.stream(prompt)
                .doFinally(signal -> metricsService.recordModelCall(
                        started,
                        modelName,
                        signal == SignalType.ON_ERROR ? "error" : "success"
                ));
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }
}

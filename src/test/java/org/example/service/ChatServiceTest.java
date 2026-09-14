package org.example.service;

import org.junit.jupiter.api.Test;
import org.example.observability.InstrumentedToolCallback;
import org.example.observability.ToolInvocationAuditService;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ChatServiceTest {

    @Test
    void createsCompatibleModeChatApiEndpoint() {
        ChatService chatService = new ChatService();
        ReflectionTestUtils.setField(chatService, "dashScopeApiKey", "test-api-key");
        ReflectionTestUtils.setField(
                chatService,
                "chatBaseUrl",
                "https://dashscope.aliyuncs.com/compatible-mode/v1"
        );
        ReflectionTestUtils.setField(chatService, "chatCompletionsPath", "/chat/completions");

        OpenAiApi openAiApi = chatService.createChatApi();

        assertThat(ReflectionTestUtils.getField(openAiApi, "baseUrl"))
                .isEqualTo("https://dashscope.aliyuncs.com/compatible-mode/v1");
        assertThat(ReflectionTestUtils.getField(openAiApi, "completionsPath"))
                .isEqualTo("/chat/completions");
    }

    @Test
    void detectsPlaceholderDashScopeApiKey() {
        ChatService chatService = new ChatService();
        ReflectionTestUtils.setField(chatService, "dashScopeApiKey", "your-api-key-here");

        assertThat(chatService.isDashScopeApiKeyConfigured()).isFalse();
    }

    @Test
    void detectsConfiguredDashScopeApiKey() {
        ChatService chatService = new ChatService();
        ReflectionTestUtils.setField(chatService, "dashScopeApiKey", "valid-api-key");

        assertThat(chatService.isDashScopeApiKeyConfigured()).isTrue();
    }

    @Test
    void disablesThinkingForCompatibleModeChatModel() {
        ChatService chatService = new ChatService();
        ReflectionTestUtils.setField(chatService, "chatModelName", "qwen3.7-flash");
        ReflectionTestUtils.setField(chatService, "enableThinking", false);

        OpenAiApi openAiApi = OpenAiApi.builder()
                .apiKey("test-api-key")
                .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
                .completionsPath("/chat/completions")
                .build();
        ChatModel chatModel = chatService.createChatModel(openAiApi, 0.7, 2000, 0.9);

        OpenAiChatOptions options = (OpenAiChatOptions) ((OpenAiChatModel) chatModel).getDefaultOptions();
        assertThat(options.getExtraBody()).containsEntry("enable_thinking", false);
    }

    @Test
    void wrapsExternalToolCallbacksWithAudit() {
        ChatService chatService = new ChatService();
        ReflectionTestUtils.setField(
                chatService,
                "tools",
                (ToolCallbackProvider) () -> new ToolCallback[]{new SearchLogCallback()}
        );
        ReflectionTestUtils.setField(
                chatService,
                "toolInvocationAuditService",
                mock(ToolInvocationAuditService.class)
        );

        ToolCallback[] callbacks = chatService.getToolCallbacks();

        assertThat(callbacks).hasSize(1);
        assertThat(callbacks[0]).isInstanceOf(InstrumentedToolCallback.class);
        assertThat(callbacks[0].getToolDefinition().name()).isEqualTo("SearchLog");
    }

    private static class SearchLogCallback implements ToolCallback {
        @Override
        public ToolDefinition getToolDefinition() {
            return new ToolDefinition() {
                @Override
                public String name() {
                    return "SearchLog";
                }

                @Override
                public String description() {
                    return "Search Tencent Cloud CLS logs";
                }

                @Override
                public String inputSchema() {
                    return "{}";
                }
            };
        }

        @Override
        public String call(String toolInput) {
            return "{\"success\":true}";
        }
    }
}

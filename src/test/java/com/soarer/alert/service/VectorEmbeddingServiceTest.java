package com.soarer.alert.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 VectorEmbeddingService 的行为。
 */
class VectorEmbeddingServiceTest {

    @Test
    void initDoesNotFailStartupWhenApiKeyIsMissing() {
        VectorEmbeddingService service = new VectorEmbeddingService();
        ReflectionTestUtils.setField(service, "apiKey", "your-api-key-here");
        ReflectionTestUtils.setField(service, "model", "text-embedding-v3");

        assertThatCode(service::init).doesNotThrowAnyException();
    }

    @Test
    void generateEmbeddingFailsClearlyWhenApiKeyIsMissing() {
        VectorEmbeddingService service = new VectorEmbeddingService();
        ReflectionTestUtils.setField(service, "apiKey", " ");
        ReflectionTestUtils.setField(service, "model", "text-embedding-v3");
        service.init();

        assertThatThrownBy(() -> service.generateEmbedding("test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DASHSCOPE_API_KEY");
    }

    @Test
    void generateEmbeddingsFailsClearlyWhenApiKeyIsMissing() {
        VectorEmbeddingService service = new VectorEmbeddingService();
        ReflectionTestUtils.setField(service, "apiKey", null);
        ReflectionTestUtils.setField(service, "model", "text-embedding-v3");
        service.init();

        assertThatThrownBy(() -> service.generateEmbeddings(List.of("test")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DASHSCOPE_API_KEY");
    }
}

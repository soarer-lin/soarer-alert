package com.soarer.alert.config;

import com.soarer.alert.service.VectorEmbeddingService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmbeddingModelConfigTest {

    @Test
    void batchesDashScopeRequestsAndPreservesVectorOrder() {
        RecordingEmbeddingService embeddingService = new RecordingEmbeddingService(2);
        EmbeddingModel model = new EmbeddingModelConfig(2).dashScopeEmbeddingModel(embeddingService);

        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            texts.add("runbook-" + i);
        }

        EmbeddingResponse response = model.call(new EmbeddingRequest(texts, null));

        assertThat(embeddingService.batchSizes).containsExactly(10, 2);
        assertThat(response.getResults()).hasSize(12);
        assertThat(response.getResults())
                .allSatisfy(embedding -> {
                    assertThat(embedding.getIndex()).isBetween(0, 11);
                    assertThat(embedding.getOutput()).containsExactly(1.0f, 2.0f);
                });
    }

    @Test
    void rejectsMismatchedVectorCount() {
        VectorEmbeddingService embeddingService = new VectorEmbeddingService() {
            @Override
            public List<List<Float>> generateEmbeddings(List<String> contents) {
                return List.of(List.of(1.0f, 2.0f));
            }
        };
        EmbeddingModel model = new EmbeddingModelConfig(2).dashScopeEmbeddingModel(embeddingService);

        List<String> texts = List.of("alpha", "beta");
        EmbeddingRequest request = new EmbeddingRequest(texts, null);

        assertThatThrownBy(() -> model.call(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("返回向量数量与输入数量不一致");
    }

    @Test
    void rejectsMismatchedDimensions() {
        VectorEmbeddingService embeddingService = new VectorEmbeddingService() {
            @Override
            public List<Float> generateEmbedding(String content) {
                return List.of(1.0f, 2.0f, 3.0f);
            }
        };
        EmbeddingModel model = new EmbeddingModelConfig(2).dashScopeEmbeddingModel(embeddingService);

        Document document = new Document("test-document", "restart the service", Map.of());

        assertThatThrownBy(() -> model.embed(document))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Embedding 维度不匹配");
    }

    @Test
    void exposesConfiguredDimensions() {
        EmbeddingModel model = new EmbeddingModelConfig(1024)
                .dashScopeEmbeddingModel(new RecordingEmbeddingService(1024));

        assertThat(model.dimensions()).isEqualTo(1024);
    }

    private static final class RecordingEmbeddingService extends VectorEmbeddingService {
        private final int dimensions;
        private final List<Integer> batchSizes = new ArrayList<>();

        private RecordingEmbeddingService(int dimensions) {
            this.dimensions = dimensions;
        }

        @Override
        public List<List<Float>> generateEmbeddings(List<String> contents) {
            batchSizes.add(contents.size());
            List<List<Float>> vectors = new ArrayList<>(contents.size());
            for (int i = 0; i < contents.size(); i++) {
                List<Float> vector = new ArrayList<>(dimensions);
                for (int j = 0; j < dimensions; j++) {
                    vector.add((float) j + 1);
                }
                vectors.add(vector);
            }
            return vectors;
        }
    }
}

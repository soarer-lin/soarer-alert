package com.soarer.alert.config;

import com.soarer.alert.service.VectorEmbeddingService;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Bridges the existing DashScope SDK service into Spring AI's EmbeddingModel contract.
 */
@Configuration
public class EmbeddingModelConfig {

    private static final int DASHSCOPE_MAX_BATCH_SIZE = 10;

    private final int configuredDimensions;

    public EmbeddingModelConfig(
            @Value("${spring.ai.vectorstore.pgvector.dimensions:1024}") int configuredDimensions
    ) {
        this.configuredDimensions = configuredDimensions;
    }

    @Bean
    public EmbeddingModel dashScopeEmbeddingModel(VectorEmbeddingService embeddingService) {
        return new EmbeddingModel() {

            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                List<String> instructions = request.getInstructions();
                if (instructions == null || instructions.isEmpty()) {
                    return new EmbeddingResponse(List.of());
                }

                List<Embedding> embeddings = new ArrayList<>();
                for (int start = 0; start < instructions.size(); start += DASHSCOPE_MAX_BATCH_SIZE) {
                    int end = Math.min(start + DASHSCOPE_MAX_BATCH_SIZE, instructions.size());
                    List<String> batch = instructions.subList(start, end);
                    List<List<Float>> vectors = embeddingService.generateEmbeddings(batch);
                    if (vectors.size() != batch.size()) {
                        throw new IllegalStateException("DashScope 返回向量数量与输入数量不一致");
                    }

                    for (int i = 0; i < vectors.size(); i++) {
                        float[] vector = toFloatArray(vectors.get(i));
                        validateDimensions(vector);
                        embeddings.add(new Embedding(vector, start + i));
                    }
                }
                return new EmbeddingResponse(embeddings);
            }

            @Override
            public float[] embed(Document document) {
                float[] vector = toFloatArray(embeddingService.generateEmbedding(document.getText()));
                validateDimensions(vector);
                return vector;
            }

            @Override
            public int dimensions() {
                return configuredDimensions;
            }
        };
    }

    private void validateDimensions(float[] vector) {
        if (vector.length != configuredDimensions) {
            throw new IllegalStateException(String.format(
                    "Embedding 维度不匹配: DashScope 返回 %d, pgvector 配置为 %d",
                    vector.length,
                    configuredDimensions
            ));
        }
    }

    private static float[] toFloatArray(List<Float> values) {
        float[] vector = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            vector[i] = values.get(i);
        }
        return vector;
    }
}

package com.soarer.alert.service;

import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Searches operations knowledge chunks through the Spring AI VectorStore API.
 */
@Service
public class VectorSearchService {

    private static final Logger logger = LoggerFactory.getLogger(VectorSearchService.class);

    private final VectorStore vectorStore;

    public VectorSearchService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * Searches chunks similar to the query text.
     *
     * @param query query text
     * @param topK maximum result count
     * @return search results
     */
    public List<SearchResult> searchSimilarDocuments(String query, int topK) {
        try {
            int normalizedTopK = Math.max(topK, 1);
            logger.info("开始搜索相似文档, topK: {}", normalizedTopK);

            List<Document> documents = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(query)
                            .topK(normalizedTopK)
                            .build()
            );

            List<SearchResult> results = new ArrayList<>();
            if (documents != null) {
                for (Document document : documents) {
                    SearchResult result = new SearchResult();
                    result.setId(document.getId());
                    result.setContent(document.getText());
                    Double score = document.getScore();
                    result.setScore(score == null ? 0.0f : score.floatValue());
                    result.setMetadata(String.valueOf(document.getMetadata()));
                    results.add(result);
                }
            }

            logger.info("搜索完成, 找到 {} 个相似文档", results.size());
            return results;
        } catch (Exception e) {
            logger.error("搜索相似文档失败", e);
            throw new RuntimeException("搜索失败: " + e.getMessage(), e);
        }
    }

    @Setter
    @Getter
    public static class SearchResult {
        private String id;
        private String content;
        private float score;
        private String metadata;
    }
}

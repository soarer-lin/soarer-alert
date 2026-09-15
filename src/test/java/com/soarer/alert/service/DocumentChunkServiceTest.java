package com.soarer.alert.service;

import com.soarer.alert.config.DocumentChunkConfig;
import com.soarer.alert.dto.DocumentChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 DocumentChunkService 的行为。
 */
class DocumentChunkServiceTest {

    private DocumentChunkService service;

    @BeforeEach
    void setUp() {
        DocumentChunkConfig config = new DocumentChunkConfig();
        config.setMaxSize(160);
        config.setOverlap(20);
        service = new DocumentChunkService(config);
    }

    @Test
    void splitsMarkdownByHeadingsAndKeepsCodeBlocksIntact() {
        String content = """
                # Deployment

                Restart the API deployment.

                ```bash
                kubectl -n prod rollout restart deployment/api
                kubectl -n prod get pods
                ```

                # Logs

                Use the following command to inspect logs.
                """;

        List<DocumentChunk> chunks = service.chunkDocument(content, "runbook.md");

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).getTitle()).isEqualTo("Deployment");
        assertThat(chunks.get(0).getChunkType()).isEqualTo("MIXED");
        assertThat(chunks.get(0).getContent()).contains("```bash");
        assertThat(chunks.get(0).getContent()).contains("kubectl -n prod rollout restart deployment/api");
        assertThat(chunks.get(1).getTitle()).isEqualTo("Logs");
        assertThat(chunks.get(1).getChunkType()).isEqualTo("TEXT");
    }

    @Test
    void splitsLongCodeBlocksAtLineBoundaries() {
        StringBuilder content = new StringBuilder("```bash\n");
        for (int i = 0; i < 30; i++) {
            content.append("kubectl_command_with_long_name_").append(i).append('\n');
        }
        content.append("```");

        DocumentChunkConfig config = new DocumentChunkConfig();
        config.setMaxSize(180);
        config.setOverlap(20);
        DocumentChunkService longCodeService = new DocumentChunkService(config);

        List<DocumentChunk> chunks = longCodeService.chunkDocument(content.toString(), "commands.md");

        assertThat(chunks.size()).isGreaterThan(1);
        assertThat(chunks).allMatch(chunk -> "CODE".equals(chunk.getChunkType()));
        assertThat(chunks.get(0).getContent()).startsWith("```bash");
        assertThat(chunks.get(chunks.size() - 1).getContent()).endsWith("```");
        assertThat(chunks)
                .allSatisfy(chunk -> chunk.getContent()
                        .lines()
                        .allMatch(line -> line.startsWith("```")
                                || line.startsWith("kubectl_command_with_long_name_")));
    }
}

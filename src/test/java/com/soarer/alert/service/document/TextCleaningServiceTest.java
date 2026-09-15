package com.soarer.alert.service.document;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 TextCleaningService 的行为。
 */
class TextCleaningServiceTest {

    private final TextCleaningService service = new TextCleaningService();

    @Test
    void preservesOperationsArtifacts() {
        String text = """
                kubectl -n prod logs deploy/api --tail=100

                ```yaml
                server:
                  port: 9900
                ```

                ```java
                throw new IllegalStateException("connection pool exhausted");
                ```

                | Service | Error |
                |---------|-------|
                | api     | 500   |

                2026-09-03T10:00:00 ERROR [api] Timeout: https://metrics.example.com
                """;

        String cleaned = service.cleanText(text);

        assertThat(cleaned).contains("kubectl -n prod logs deploy/api --tail=100");
        assertThat(cleaned).contains("server:");
        assertThat(cleaned).contains("throw new IllegalStateException");
        assertThat(cleaned).contains("| api     | 500   |");
        assertThat(cleaned).contains("https://metrics.example.com");
    }

    @Test
    void normalizesLineEndingsAndRemovesControlCharacters() {
        String text = "first\r\nsecond\r\r\nthird" + '\u0000' + "\r\n\n\n\n";

        String cleaned = service.cleanText(text);

        assertThat(cleaned).isEqualTo("first\nsecond\n\nthird");
    }
}

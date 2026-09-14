package org.example.service.document;

import org.example.config.DocumentParseConfig;
import org.example.dto.ParsedDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentParseServiceTest {

    private DocumentParseService service;

    @BeforeEach
    void setUp() {
        DocumentParseConfig config = new DocumentParseConfig();
        config.setMaxTextChars(10_000);
        service = new DocumentParseService(config, new TextCleaningService());
    }

    @Test
    void parsesUtf8TextAndDropsBomFromContent() {
        String text = "# Title\nkubectl get pods";
        byte[] encoded = text.getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[encoded.length + 3];
        bytes[0] = (byte) 0xEF;
        bytes[1] = (byte) 0xBB;
        bytes[2] = (byte) 0xBF;
        System.arraycopy(encoded, 0, bytes, 3, encoded.length);

        ParsedDocument document = service.parse(bytes, "runbook.txt");

        assertThat(document.isUtf8Bom()).isTrue();
        assertThat(document.getContent()).isEqualTo(text);
        assertThat(document.getMediaType()).isEqualTo("text/plain");
    }

    @Test
    void rejectsNonUtf8Text() {
        byte[] bytes = {(byte) 0xFF, (byte) 0xFE, 'a', 'b'};

        assertThatThrownBy(() -> service.parse(bytes, "invalid.txt"))
                .isInstanceOf(DocumentProcessingException.class)
                .hasMessageContaining("不是有效 UTF-8");
    }
}

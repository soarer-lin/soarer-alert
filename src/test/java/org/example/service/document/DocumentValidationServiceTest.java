package org.example.service.document;

import org.example.config.FileUploadConfig;
import org.example.dto.ValidatedDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentValidationServiceTest {

    private DocumentValidationService service;

    @BeforeEach
    void setUp() {
        FileUploadConfig config = new FileUploadConfig();
        config.setPath("./uploads");
        config.setAllowedExtensions("txt,md,markdown,pdf,doc,docx");
        config.setMaxSizeBytes(1024);
        service = new DocumentValidationService(config);
    }

    @Test
    void validatesTextFileAndDetectedMediaType() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "runbook.txt",
                "text/plain",
                "kubectl get pods".getBytes(StandardCharsets.UTF_8)
        );

        ValidatedDocument document = service.validate(file);

        assertThat(document.getExtension()).isEqualTo("txt");
        assertThat(document.getMediaType()).isEqualTo("text/plain");
        assertThat(document.getSize()).isEqualTo(file.getSize());
    }

    @Test
    void validatesMarkdownDetectedAsWebMarkdown() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "rustfs-runbook.md",
                "text/markdown",
                "# RustFS runbook\n\n- Check bucket health\n".getBytes(StandardCharsets.UTF_8)
        );

        ValidatedDocument document = service.validate(file);

        assertThat(document.getExtension()).isEqualTo("md");
        assertThat(document.getMediaType()).isEqualTo("text/x-web-markdown");
    }

    @Test
    void rejectsExtensionAndDetectedTypeMismatch() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "fake.txt",
                "text/plain",
                "%PDF-1.4\n%superbiz\n".getBytes(StandardCharsets.ISO_8859_1)
        );

        assertThatThrownBy(() -> service.validate(file))
                .isInstanceOf(DocumentProcessingException.class)
                .hasMessageContaining("文件真实类型与扩展名不一致");
    }
}

package com.soarer.alert.service;

import lombok.Getter;
import lombok.Setter;
import com.soarer.alert.dto.DocumentChunk;
import com.soarer.alert.dto.ParsedDocument;
import com.soarer.alert.dto.ValidatedDocument;
import com.soarer.alert.entity.OpsDocument;
import com.soarer.alert.service.document.ContentHashService;
import com.soarer.alert.service.document.DocumentParseService;
import com.soarer.alert.service.document.DocumentValidationService;
import com.soarer.alert.service.persistence.DocumentPersistenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads local documents, reuses the stage-3 parsing pipeline, and stores chunks
 * in PostgreSQL through Spring AI's VectorStore abstraction.
 */
@Service
public class VectorIndexService {

    private static final Logger logger = LoggerFactory.getLogger(VectorIndexService.class);

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final DocumentChunkService chunkService;
    private final DocumentValidationService validationService;
    private final DocumentParseService parseService;
    private final ContentHashService hashService;
    private final DocumentPersistenceService documentPersistenceService;
    private final String uploadPath;

    public VectorIndexService(
            VectorStore vectorStore,
            JdbcTemplate jdbcTemplate,
            DocumentChunkService chunkService,
            DocumentValidationService validationService,
            DocumentParseService parseService,
            ContentHashService hashService,
            DocumentPersistenceService documentPersistenceService,
            @Value("${file.upload.path}") String uploadPath
    ) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.chunkService = chunkService;
        this.validationService = validationService;
        this.parseService = parseService;
        this.hashService = hashService;
        this.documentPersistenceService = documentPersistenceService;
        this.uploadPath = uploadPath;
    }

    /**
     * Indexes every supported file in a directory.
     *
     * @param directoryPath optional directory, defaults to the configured upload path
     * @return indexing summary
     */
    public IndexingResult indexDirectory(String directoryPath) {
        IndexingResult result = new IndexingResult();
        result.setStartTime(LocalDateTime.now());

        try {
            String targetPath = (directoryPath != null && !directoryPath.trim().isEmpty())
                    ? directoryPath : uploadPath;
            Path dirPath = Paths.get(targetPath).normalize();
            File directory = dirPath.toFile();

            if (!directory.exists() || !directory.isDirectory()) {
                throw new IllegalArgumentException("目录不存在或不是有效目录: " + targetPath);
            }

            result.setDirectoryPath(directory.getAbsolutePath());
            File[] files = directory.listFiles((dir, name) -> validationService.isAllowedExtension(name));
            if (files == null || files.length == 0) {
                logger.warn("目录中没有找到支持的文件: {}", targetPath);
                result.setTotalFiles(0);
                result.setSuccess(true);
                result.setEndTime(LocalDateTime.now());
                return result;
            }

            result.setTotalFiles(files.length);
            logger.info("开始索引目录: {}, 找到 {} 个文件", targetPath, files.length);

            for (File file : files) {
                try {
                    indexSingleFile(file.getAbsolutePath());
                    result.incrementSuccessCount();
                    logger.info("✓ 文件索引成功: {}", file.getName());
                } catch (Exception e) {
                    result.incrementFailCount();
                    result.addFailedFile(file.getAbsolutePath(), e.getMessage());
                    logger.error("✗ 文件索引失败: {}", file.getName(), e);
                }
            }

            result.setSuccess(result.getFailCount() == 0);
            result.setEndTime(LocalDateTime.now());
            logger.info("目录索引完成: 总数={}, 成功={}, 失败={}",
                    result.getTotalFiles(), result.getSuccessCount(), result.getFailCount());
            return result;
        } catch (Exception e) {
            logger.error("索引目录失败", e);
            result.setSuccess(false);
            result.setErrorMessage(e.getMessage());
            result.setEndTime(LocalDateTime.now());
            return result;
        }
    }

    /**
     * Indexes a single local file.
     *
     * @param filePath file path
     * @throws Exception when validation, parsing, or indexing fails
     */
    public void indexSingleFile(String filePath) throws Exception {
        Path path = Paths.get(filePath).normalize();
        File file = path.toFile();
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("文件不存在: " + filePath);
        }

        logger.info("开始索引文件: {}", path);
        ValidatedDocument validatedDocument = validationService.validate(path);
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new IllegalStateException("读取文件失败: " + e.getMessage(), e);
        }

        ParsedDocument document = parseService.parse(bytes, validatedDocument.getFileName());
        document.setContentHash(hashService.sha256(bytes));
        List<DocumentChunk> chunks = chunkService.chunkDocument(document.getContent(), path.toString());

        Optional<OpsDocument> existing = documentPersistenceService.findExisting(document.getContentHash());
        if (existing.isPresent()
                && (existing.get().getStorageKey() != null
                || !Paths.get(existing.get().getFilePath()).normalize().equals(path.normalize()))) {
            logger.warn("跳过重复文档: {}, 已存在: {}", path, existing.get().getFilePath());
            return;
        }

        OpsDocument persistedDocument = documentPersistenceService.registerLocalDocument(
                validatedDocument,
                document.getContentHash(),
                path,
                null
        );
        documentPersistenceService.markIndexing(persistedDocument.getId());
        indexDocument(document, path, chunks);
        documentPersistenceService.markIndexingSuccess(persistedDocument.getId(), chunks);
    }

    /**
     * Indexes a document that has already been validated, parsed, hashed, and chunked.
     */
    public void indexDocument(ParsedDocument document, Path path, List<DocumentChunk> chunks) {
        indexDocument(document, normalizePath(path), chunks);
    }

    /**
     * Indexes a document uploaded to object storage. The source identifier is its stable object key.
     */
    public void indexDocument(ParsedDocument document, String source, List<DocumentChunk> chunks) {
        logger.info("开始索引文件: {}, 分片数: {}", source, chunks == null ? 0 : chunks.size());
        int deletedCount = deleteExistingData(source);

        if (chunks == null || chunks.isEmpty()) {
            logger.warn("文档没有可索引文本: {}", source);
            return;
        }

        List<Document> documents = new ArrayList<>(chunks.size());
        for (DocumentChunk chunk : chunks) {
            Map<String, Object> metadata = buildMetadata(document, source, chunk, chunks.size());
            String id = documentId(source, chunk.getChunkIndex());
            documents.add(new Document(id, chunk.getContent(), metadata));
        }

        vectorStore.add(documents);
        logger.info("文件索引完成: {}, 共 {} 个分片，替换旧记录 {} 条", source, chunks.size(), deletedCount);
    }

    /**
     * Replaces all chunks previously indexed for the same source path.
     */
    public int deleteDocumentVectors(String source) {
        if (source == null || source.isBlank()) {
            return 0;
        }
        return deleteExistingData(source);
    }

    private int deleteExistingData(String source) {
        String normalizedPath = normalizeSource(source);
        int deletedCount = jdbcTemplate.update(
                "DELETE FROM vector_store WHERE metadata->>'_source' = ?",
                normalizedPath
        );
        if (deletedCount > 0) {
            logger.info("✓ 已删除文件的旧数据: {}, 删除记录数: {}", normalizedPath, deletedCount);
        }
        return deletedCount;
    }

    private Map<String, Object> buildMetadata(
            ParsedDocument document,
            String source,
            DocumentChunk chunk,
            int totalChunks
    ) {
        Map<String, Object> metadata = new HashMap<>();
        String normalizedPath = normalizeSource(source);
        String fileNameStr = fileName(source);
        String extension = "";
        int dotIndex = fileNameStr.lastIndexOf('.');
        if (dotIndex > 0) {
            extension = fileNameStr.substring(dotIndex);
        }

        metadata.put("_source", normalizedPath);
        metadata.put("_extension", extension);
        metadata.put("_file_name", fileNameStr);
        metadata.put("_media_type", document.getMediaType());
        metadata.put("_content_hash", document.getContentHash());
        metadata.put("_byte_size", document.getByteSize());
        metadata.put("chunkIndex", chunk.getChunkIndex());
        metadata.put("totalChunks", totalChunks);

        if (chunk.getTitle() != null && !chunk.getTitle().isEmpty()) {
            metadata.put("title", chunk.getTitle());
        }
        if (chunk.getChunkType() != null && !chunk.getChunkType().isEmpty()) {
            metadata.put("chunkType", chunk.getChunkType());
        }
        return metadata;
    }

    private String normalizePath(Path path) {
        return normalizeSource(path.normalize().toString());
    }

    private String normalizeSource(String source) {
        return source.trim().replace(File.separator, "/");
    }

    private String fileName(String source) {
        int slashIndex = source.lastIndexOf('/');
        return slashIndex >= 0 && slashIndex < source.length() - 1
                ? source.substring(slashIndex + 1)
                : source;
    }

    private String documentId(String source, int chunkIndex) {
        String normalizedSource = normalizeSource(source);
        return UUID.nameUUIDFromBytes((normalizedSource + "_" + chunkIndex).getBytes()).toString();
    }

    @Getter
    public static class IndexingResult {
        @Setter
        private boolean success;
        @Setter
        private String directoryPath;
        @Setter
        private int totalFiles;
        private int successCount;
        private int failCount;
        @Setter
        private LocalDateTime startTime;
        @Setter
        private LocalDateTime endTime;
        @Setter
        private String errorMessage;
        private final Map<String, String> failedFiles = new HashMap<>();

        public void incrementSuccessCount() {
            this.successCount++;
        }

        public void incrementFailCount() {
            this.failCount++;
        }

        public long getDurationMs() {
            if (startTime != null && endTime != null) {
                return java.time.Duration.between(startTime, endTime).toMillis();
            }
            return 0;
        }

        public void addFailedFile(String filePath, String error) {
            this.failedFiles.put(filePath, error);
        }
    }
}

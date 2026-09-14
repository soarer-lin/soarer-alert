package com.soarer.alert.service.document;

import org.apache.tika.Tika;
import com.soarer.alert.config.FileUploadConfig;
import com.soarer.alert.dto.ValidatedDocument;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class DocumentValidationService {

    private final FileUploadConfig uploadConfig;
    private final Tika tika = new Tika();

    public DocumentValidationService(FileUploadConfig uploadConfig) {
        this.uploadConfig = uploadConfig;
    }

    public ValidatedDocument validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new DocumentProcessingException("文件不能为空");
        }
        String fileName = safeFileName(file.getOriginalFilename());
        String mediaType = detectContentType(file, fileName);
        return buildResult(fileName, mediaType, file.getSize());
    }

    public ValidatedDocument validate(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            throw new DocumentProcessingException("文件不存在: " + path);
        }
        String fileName = safeFileName(path.getFileName() != null ? path.getFileName().toString() : null);
        String mediaType = detectContentType(path);
        try {
            return buildResult(fileName, mediaType, Files.size(path));
        } catch (IOException e) {
            throw new DocumentProcessingException("读取文件大小失败: " + e.getMessage(), e);
        }
    }

    public boolean isAllowedExtension(String fileName) {
        String extension = extensionOf(fileName);
        return !extension.isBlank() && allowedExtensions().contains(extension);
    }

    public String safeFileName(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new DocumentProcessingException("文件名不能为空");
        }
        String normalized = originalFilename.replace('\\', '/');
        int lastSlash = normalized.lastIndexOf('/');
        String fileName = lastSlash >= 0 ? normalized.substring(lastSlash + 1) : normalized;
        fileName = fileName.replaceAll("\\p{Cntrl}", "_").strip();
        if (fileName.isBlank() || fileName.equals(".") || fileName.equals("..")) {
            throw new DocumentProcessingException("文件名不合法");
        }
        return fileName;
    }

    private ValidatedDocument buildResult(String fileName, String mediaType, long size) {
        String extension = extensionOf(fileName);
        if (!allowedExtensions().contains(extension)) {
            throw new DocumentProcessingException(
                    "不支持的文件格式，仅支持: " + uploadConfig.getAllowedExtensions()
            );
        }
        if (size <= 0) {
            throw new DocumentProcessingException("文件不能为空");
        }
        if (size > uploadConfig.getMaxSizeBytes()) {
            throw new DocumentProcessingException(
                    "文件大小超过限制: " + uploadConfig.getMaxSizeBytes() + " 字节"
            );
        }
        if (!isSupportedMediaType(mediaType) || !extensionMatchesMediaType(extension, mediaType)) {
            throw new DocumentProcessingException(
                    "文件真实类型与扩展名不一致: 扩展名=" + extension
                            + ", 检测类型=" + mediaType
            );
        }
        return new ValidatedDocument(fileName, extension, mediaType, size);
    }

    private String detectContentType(MultipartFile file, String fileName) {
        try (InputStream inputStream = file.getInputStream()) {
            return tika.detect(inputStream, fileName);
        } catch (IOException e) {
            throw new DocumentProcessingException("检测文件真实类型失败: " + e.getMessage(), e);
        }
    }

    private String detectContentType(Path path) {
        try (InputStream inputStream = Files.newInputStream(path)) {
            String fileName = path.getFileName() != null ? path.getFileName().toString() : null;
            return tika.detect(inputStream, fileName);
        } catch (IOException e) {
            throw new DocumentProcessingException("检测文件真实类型失败: " + e.getMessage(), e);
        }
    }

    private Set<String> allowedExtensions() {
        if (uploadConfig.getAllowedExtensions() == null || uploadConfig.getAllowedExtensions().isBlank()) {
            return Set.of();
        }
        return Arrays.stream(uploadConfig.getAllowedExtensions().split(","))
                .map(String::trim)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    private String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    private boolean isSupportedMediaType(String mediaType) {
        String normalized = mediaType == null ? "" : mediaType.toLowerCase(Locale.ROOT);
        return normalized.equals("text/plain")
                || normalized.equals("text/markdown")
                || normalized.equals("text/x-markdown")
                || normalized.equals("text/x-web-markdown")
                || normalized.equals("application/pdf")
                || normalized.equals("application/msword")
                || normalized.equals("application/x-tika-msoffice")
                || normalized.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    }

    private boolean extensionMatchesMediaType(String extension, String mediaType) {
        String normalized = mediaType == null ? "" : mediaType.toLowerCase(Locale.ROOT);
        switch (extension) {
            case "txt":
                return normalized.equals("text/plain");
            case "md":
            case "markdown":
                return normalized.equals("text/plain")
                        || normalized.equals("text/markdown")
                        || normalized.equals("text/x-markdown")
                        || normalized.equals("text/x-web-markdown");
            case "pdf":
                return normalized.equals("application/pdf");
            case "doc":
                return normalized.equals("application/msword")
                        || normalized.equals("application/x-tika-msoffice");
            case "docx":
                return normalized.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            default:
                return false;
        }
    }

}

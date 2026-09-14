package com.soarer.alert.service.document;

import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import com.soarer.alert.config.DocumentParseConfig;
import com.soarer.alert.dto.ParsedDocument;
import org.springframework.stereotype.Service;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Service
public class DocumentParseService {

    private final DocumentParseConfig parseConfig;
    private final TextCleaningService cleaningService;
    private final org.apache.tika.Tika tika = new org.apache.tika.Tika();

    public DocumentParseService(DocumentParseConfig parseConfig, TextCleaningService cleaningService) {
        this.parseConfig = parseConfig;
        this.cleaningService = cleaningService;
    }

    public ParsedDocument parse(byte[] bytes, String fileName) {
        if (bytes == null || bytes.length == 0) {
            throw new DocumentProcessingException("文件内容为空，无法解析");
        }

        String mediaType = tika.detect(bytes, fileName);
        String content = isTextMediaType(mediaType)
                ? parseText(bytes)
                : parseBinary(bytes, fileName);
        String cleaned = cleaningService.cleanText(content);
        if (cleaned.isBlank()) {
            throw new DocumentProcessingException("文档解析结果为空: " + fileName);
        }
        if (cleaned.length() > parseConfig.getMaxTextChars()) {
            throw new DocumentProcessingException(
                    "提取文本超过限制: " + cleaned.length()
                            + " 字符，最大 " + parseConfig.getMaxTextChars() + " 字符"
            );
        }
        return new ParsedDocument(
                fileName,
                mediaType,
                cleaned,
                bytes.length,
                hasUtf8Bom(bytes) && isTextMediaType(mediaType)
        );
    }

    public ParsedDocument parse(Path path) {
        if (path == null || !java.nio.file.Files.isRegularFile(path)) {
            throw new DocumentProcessingException("文件不存在: " + path);
        }
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(path);
            String fileName = path.getFileName() != null ? path.getFileName().toString() : path.toString();
            return parse(bytes, fileName);
        } catch (IOException e) {
            throw new DocumentProcessingException("读取文档失败: " + e.getMessage(), e);
        }
    }

    private String parseText(byte[] bytes) {
        boolean bom = hasUtf8Bom(bytes);
        int offset = bom ? 3 : 0;
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
        } catch (CharacterCodingException e) {
            throw new DocumentProcessingException("文本文档不是有效 UTF-8: " + e.getMessage(), e);
        }
    }

    private String parseBinary(byte[] bytes, String fileName) {
        AutoDetectParser parser = new AutoDetectParser();
        BodyContentHandler handler = new BodyContentHandler(parseConfig.getMaxTextChars() + 1);
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);

        ParseContext context = new ParseContext();
        context.set(Parser.class, parser);
        context.set(org.apache.tika.extractor.EmbeddedDocumentExtractor.class, new NoOpEmbeddedDocumentExtractor());

        PDFParserConfig pdfConfig = new PDFParserConfig();
        pdfConfig.setExtractInlineImages(false);
        pdfConfig.setSortByPosition(true);
        context.set(PDFParserConfig.class, pdfConfig);

        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(bytes)) {
            parser.parse(inputStream, handler, metadata, context);
            return handler.toString();
        } catch (IOException | TikaException | SAXException e) {
            throw new DocumentProcessingException("文档解析失败: " + e.getMessage(), e);
        }
    }

    private boolean isTextMediaType(String mediaType) {
        String normalized = mediaType == null ? "" : mediaType.toLowerCase(Locale.ROOT);
        return normalized.equals("text/plain")
                || normalized.equals("text/markdown")
                || normalized.equals("text/x-markdown");
    }

    private boolean hasUtf8Bom(byte[] bytes) {
        return bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF;
    }
}

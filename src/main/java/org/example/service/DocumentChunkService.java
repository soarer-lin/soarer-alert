package org.example.service;

import org.example.config.DocumentChunkConfig;
import org.example.dto.DocumentChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Splits operations documents at Markdown headings, paragraphs, and fenced code blocks.
 */
@Service
public class DocumentChunkService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentChunkService.class);

    private final DocumentChunkConfig chunkConfig;

    public DocumentChunkService(DocumentChunkConfig chunkConfig) {
        this.chunkConfig = chunkConfig;
    }

    public List<DocumentChunk> chunkDocument(String content, String filePath) {
        List<DocumentChunk> chunks = new ArrayList<>();
        if (content == null || content.isBlank()) {
            logger.warn("文档内容为空: {}", filePath);
            return chunks;
        }

        List<Section> sections = splitByHeadings(content);
        int globalChunkIndex = 0;
        for (Section section : sections) {
            List<DocumentChunk> sectionChunks = chunkSection(section, globalChunkIndex);
            chunks.addAll(sectionChunks);
            globalChunkIndex += sectionChunks.size();
        }

        logger.info("文档分片完成: {} -> {} 个分片", filePath, chunks.size());
        return chunks;
    }

    private List<Section> splitByHeadings(String content) {
        List<Section> sections = new ArrayList<>();
        Section current = new Section(null, new StringBuilder(), 0);
        boolean inFence = false;
        String fenceMarker = null;
        int offset = 0;

        for (String line : content.split("\n", -1)) {
            String trimmed = line.strip();
            String marker = fenceMarker(trimmed);

            if (!inFence && isHeading(trimmed)) {
                if (current.hasContent()) {
                    sections.add(current.finish());
                }
                current = new Section(headingTitle(trimmed), new StringBuilder(), offset);
            }

            current.content.append(line).append('\n');

            if (marker != null) {
                if (!inFence) {
                    inFence = true;
                    fenceMarker = marker;
                } else if (trimmed.startsWith(fenceMarker)) {
                    inFence = false;
                    fenceMarker = null;
                }
            }

            offset += line.length() + 1;
        }

        if (current.hasContent()) {
            sections.add(current.finish());
        }
        if (sections.isEmpty()) {
            sections.add(new Section(null, new StringBuilder(content), 0).finish());
        }
        return sections;
    }

    private List<DocumentChunk> chunkSection(Section section, int startChunkIndex) {
        List<DocumentChunk> chunks = new ArrayList<>();
        String content = section.content.toString();
        if (content.length() <= chunkConfig.getMaxSize()) {
            Set<BlockType> blockTypes = EnumSet.noneOf(BlockType.class);
            for (SemanticBlock block : splitIntoSemanticBlocks(content)) {
                blockTypes.add(block.type);
            }
            if (blockTypes.isEmpty()) {
                blockTypes.add(BlockType.TEXT);
            }
            DocumentChunk chunk = new DocumentChunk(
                    content,
                    section.startIndex,
                    section.startIndex + content.length(),
                    startChunkIndex
            );
            chunk.setTitle(section.title);
            chunk.setChunkType(chunkType(blockTypes));
            chunks.add(chunk);
            return chunks;
        }

        List<SemanticBlock> blocks = normalizeBlockLength(splitIntoSemanticBlocks(content));
        StringBuilder current = new StringBuilder();
        Set<BlockType> currentTypes = EnumSet.noneOf(BlockType.class);
        int currentStartIndex = section.startIndex;
        int chunkIndex = startChunkIndex;

        for (SemanticBlock block : blocks) {
            if (current.length() > 0
                    && current.length() + 2 + block.content.length() > chunkConfig.getMaxSize()) {
                if (current.toString().strip().length() <= chunkConfig.getOverlap()) {
                    current.setLength(0);
                    currentTypes.clear();
                } else {
                    FlushResult flushResult = flushCurrentChunk(
                            current,
                            currentTypes,
                            section,
                            currentStartIndex,
                            chunkIndex
                    );
                    chunks.add(flushResult.chunk);
                    chunkIndex = flushResult.nextChunkIndex;
                    current = flushResult.nextContent;
                    currentStartIndex = flushResult.nextStartIndex;
                    currentTypes = flushResult.nextTypes;
                }
            }

            if (current.length() > 0) {
                current.append('\n').append('\n');
            }
            current.append(block.content);
            currentTypes.add(block.type);
        }

        if (current.length() > 0) {
            String chunkContent = current.toString().strip();
            DocumentChunk chunk = new DocumentChunk(
                    chunkContent,
                    currentStartIndex,
                    currentStartIndex + chunkContent.length(),
                    chunkIndex
            );
            chunk.setTitle(section.title);
            chunk.setChunkType(chunkType(currentTypes));
            chunks.add(chunk);
        }
        return chunks;
    }

    private List<SemanticBlock> splitIntoSemanticBlocks(String content) {
        List<SemanticBlock> blocks = new ArrayList<>();
        StringBuilder paragraph = new StringBuilder();
        StringBuilder codeBlock = new StringBuilder();
        boolean inFence = false;
        String fenceMarker = null;

        for (String line : content.split("\n", -1)) {
            String trimmed = line.strip();
            String marker = fenceMarker(trimmed);

            if (marker != null) {
                if (!inFence) {
                    flushParagraph(blocks, paragraph);
                    inFence = true;
                    fenceMarker = marker;
                    codeBlock = new StringBuilder();
                } else if (trimmed.startsWith(fenceMarker)) {
                    inFence = false;
                    fenceMarker = null;
                }
                codeBlock.append(line).append('\n');
                if (!inFence) {
                    flushCode(blocks, codeBlock);
                }
            } else if (inFence) {
                codeBlock.append(line).append('\n');
            } else if (trimmed.isEmpty()) {
                flushParagraph(blocks, paragraph);
            } else {
                paragraph.append(line).append('\n');
            }
        }

        flushParagraph(blocks, paragraph);
        if (inFence) {
            flushCode(blocks, codeBlock);
        }
        return blocks;
    }

    private List<SemanticBlock> normalizeBlockLength(List<SemanticBlock> blocks) {
        List<SemanticBlock> normalized = new ArrayList<>();
        for (SemanticBlock block : blocks) {
            if (block.content.length() <= chunkConfig.getMaxSize()) {
                normalized.add(block);
                continue;
            }
            for (String segment : splitLongContent(block.content, block.type == BlockType.CODE)) {
                normalized.add(new SemanticBlock(segment, block.type));
            }
        }
        return normalized;
    }

    private List<String> splitLongContent(String content, boolean code) {
        List<String> segments = new ArrayList<>();
        int start = 0;
        while (start < content.length()) {
            int end = Math.min(start + chunkConfig.getMaxSize(), content.length());
            if (end < content.length()) {
                int boundary = code
                        ? content.lastIndexOf('\n', end)
                        : lastSentenceBoundary(content, start, end);
                if (boundary > start + chunkConfig.getMaxSize() / 2) {
                    end = boundary + 1;
                }
            }
            segments.add(content.substring(start, end));
            start = end;
        }
        return segments;
    }

    private int lastSentenceBoundary(String content, int start, int end) {
        int boundary = -1;
        for (int i = end - 1; i > start; i--) {
            char character = content.charAt(i);
            if (character == '.' || character == '?' || character == '!'
                    || character == '。' || character == '？' || character == '！') {
                boundary = i;
                break;
            }
        }
        return boundary;
    }

    private FlushResult flushCurrentChunk(
            StringBuilder current,
            Set<BlockType> currentTypes,
            Section section,
            int currentStartIndex,
            int chunkIndex
    ) {
        String chunkContent = current.toString().strip();
        DocumentChunk chunk = new DocumentChunk(
                chunkContent,
                currentStartIndex,
                currentStartIndex + chunkContent.length(),
                chunkIndex
        );
        chunk.setTitle(section.title);
        chunk.setChunkType(chunkType(currentTypes));

        String overlap = getOverlapText(chunkContent);
        StringBuilder nextContent = new StringBuilder(overlap);
        Set<BlockType> nextTypes = EnumSet.noneOf(BlockType.class);
        if (!overlap.isBlank()) {
            nextTypes.addAll(currentTypes);
        }
        int nextStartIndex = overlap.isBlank()
                ? currentStartIndex + chunkContent.length()
                : currentStartIndex + chunkContent.length() - overlap.length();

        return new FlushResult(chunk, chunkIndex + 1, nextContent, nextTypes, nextStartIndex);
    }

    private void flushParagraph(List<SemanticBlock> blocks, StringBuilder paragraph) {
        String content = paragraph.toString().strip();
        if (!content.isBlank()) {
            blocks.add(new SemanticBlock(content, BlockType.TEXT));
        }
        paragraph.setLength(0);
    }

    private void flushCode(List<SemanticBlock> blocks, StringBuilder codeBlock) {
        String content = codeBlock.toString().strip();
        if (!content.isBlank()) {
            blocks.add(new SemanticBlock(content, BlockType.CODE));
        }
        codeBlock.setLength(0);
    }

    private String getOverlapText(String text) {
        int overlapSize = Math.min(chunkConfig.getOverlap(), text.length());
        if (overlapSize <= 0) {
            return "";
        }
        String overlap = text.substring(text.length() - overlapSize);
        int boundary = Math.max(
                overlap.lastIndexOf('\n'),
                Math.max(
                        Math.max(overlap.lastIndexOf('.'), overlap.lastIndexOf('?')),
                        Math.max(
                                Math.max(overlap.lastIndexOf('!'), overlap.lastIndexOf('。')),
                                Math.max(overlap.lastIndexOf('？'), overlap.lastIndexOf('！'))
                        )
                )
        );
        if (boundary > overlapSize / 2) {
            return overlap.substring(boundary + 1).strip();
        }
        return overlap.strip();
    }

    private boolean isHeading(String line) {
        if (!line.startsWith("#")) {
            return false;
        }
        int level = 0;
        while (level < line.length() && line.charAt(level) == '#') {
            level++;
        }
        return level <= 6
                && level < line.length()
                && (line.charAt(level) == ' ' || line.charAt(level) == '\t')
                && !line.substring(level + 1).isBlank();
    }

    private String headingTitle(String heading) {
        int level = 0;
        while (heading.charAt(level) == '#') {
            level++;
        }
        return heading.substring(level + 1).strip();
    }

    private String fenceMarker(String line) {
        if (line.startsWith("```")) {
            return "```";
        }
        if (line.startsWith("~~~")) {
            return "~~~";
        }
        return null;
    }

    private String chunkType(Set<BlockType> types) {
        if (types.contains(BlockType.CODE) && types.contains(BlockType.TEXT)) {
            return "MIXED";
        }
        if (types.contains(BlockType.CODE)) {
            return "CODE";
        }
        return "TEXT";
    }

    private enum BlockType {
        TEXT,
        CODE
    }

    private static class SemanticBlock {
        private final String content;
        private final BlockType type;

        private SemanticBlock(String content, BlockType type) {
            this.content = content;
            this.type = type;
        }
    }

    private static class Section {
        private final String title;
        private final StringBuilder content;
        private final int startIndex;

        private Section(String title, StringBuilder content, int startIndex) {
            this.title = title;
            this.content = content;
            this.startIndex = startIndex;
        }

        private boolean hasContent() {
            return content.toString().strip().length() > 0;
        }

        private Section finish() {
            return new Section(title, new StringBuilder(content.toString().strip()), startIndex);
        }
    }

    private static class FlushResult {
        private final DocumentChunk chunk;
        private final int nextChunkIndex;
        private final StringBuilder nextContent;
        private final Set<BlockType> nextTypes;
        private final int nextStartIndex;

        private FlushResult(
                DocumentChunk chunk,
                int nextChunkIndex,
                StringBuilder nextContent,
                Set<BlockType> nextTypes,
                int nextStartIndex
        ) {
            this.chunk = chunk;
            this.nextChunkIndex = nextChunkIndex;
            this.nextContent = nextContent;
            this.nextTypes = nextTypes;
            this.nextStartIndex = nextStartIndex;
        }
    }
}

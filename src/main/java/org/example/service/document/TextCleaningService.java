package org.example.service.document;

import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * Cleans text extracted from operations documents while preserving commands,
 * configuration, code, stack traces, URLs, tables, and log samples.
 */
@Service
public class TextCleaningService {

    private static final Pattern CONTROL_CHARS =
            Pattern.compile("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]");
    private static final Pattern ZERO_WIDTH_CHARS =
            Pattern.compile("[\\u200B-\\u200F\\u202A-\\u202E\\u2060\\uFEFF]");
    private static final Pattern TRAILING_SPACES = Pattern.compile("(?m)[ \\t]+$");

    public String cleanText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        String cleaned = text
                .replace("\r\n", "\n")
                .replace("\r", "\n");
        cleaned = CONTROL_CHARS.matcher(cleaned).replaceAll("");
        cleaned = ZERO_WIDTH_CHARS.matcher(cleaned).replaceAll("");
        cleaned = TRAILING_SPACES.matcher(cleaned).replaceAll("");
        cleaned = cleaned.replaceAll("\\n{3,}", "\n\n");
        return cleaned.strip();
    }
}

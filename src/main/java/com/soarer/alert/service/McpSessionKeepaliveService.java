package com.soarer.alert.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * MCP 会话保活服务。
 */
/**
 * Keeps the SSE session used by the Tencent CLS MCP server active.
 */
@Service
@ConditionalOnProperty(
        name = "app.mcp.keepalive-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class McpSessionKeepaliveService {

    private static final Logger logger = LoggerFactory.getLogger(McpSessionKeepaliveService.class);

    private final ToolCallbackProvider toolCallbackProvider;

    public McpSessionKeepaliveService(ObjectProvider<ToolCallbackProvider> toolCallbackProvider) {
        this.toolCallbackProvider = toolCallbackProvider.getIfAvailable();
    }

    @Scheduled(
            fixedDelayString = "${app.mcp.keepalive-interval-ms:240000}",
            initialDelayString = "${app.mcp.keepalive-initial-delay-ms:120000}"
    )
    public void keepSessionAlive() {
        if (toolCallbackProvider == null) {
            return;
        }

        try {
            ToolCallback[] callbacks = toolCallbackProvider.getToolCallbacks();
            logger.debug("MCP session keepalive completed, tools: {}", callbacks.length);
        } catch (Exception failure) {
            logger.warn("MCP session keepalive failed: {}", failure.getMessage());
        }
    }
}

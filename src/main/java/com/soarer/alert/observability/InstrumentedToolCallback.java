package com.soarer.alert.observability;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Wraps MCP tool callbacks so their invocations are measured and persisted.
 */
public class InstrumentedToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final ToolInvocationAuditService auditService;

    public InstrumentedToolCallback(ToolCallback delegate, ToolInvocationAuditService auditService) {
        this.delegate = delegate;
        this.auditService = auditService;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        ToolInvocationAuditService.StartedTool startedTool = start();
        try {
            String result = delegate.call(toolInput);
            auditService.success(startedTool, result);
            return result;
        } catch (RuntimeException failure) {
            auditService.failure(startedTool, failure.getMessage());
            throw failure;
        } catch (Error failure) {
            auditService.failure(startedTool, failure.getMessage());
            throw failure;
        }
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        ToolInvocationAuditService.StartedTool startedTool = start();
        try {
            String result = delegate.call(toolInput, toolContext);
            auditService.success(startedTool, result);
            return result;
        } catch (RuntimeException failure) {
            auditService.failure(startedTool, failure.getMessage());
            throw failure;
        } catch (Error failure) {
            auditService.failure(startedTool, failure.getMessage());
            throw failure;
        }
    }

    private ToolInvocationAuditService.StartedTool start() {
        return auditService.start(delegate.getToolDefinition().name());
    }
}

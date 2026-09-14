package com.soarer.alert.observability;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import com.soarer.alert.service.observability.OpsMetricsService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class ToolInvocationObservabilityAspect {

    private final ToolInvocationAuditService auditService;

    public ToolInvocationObservabilityAspect(ToolInvocationAuditService auditService) {
        this.auditService = auditService;
    }

    @Around("execution(* com.soarer.alert..*(..)) && @annotation(tool)")
    public Object recordToolInvocation(ProceedingJoinPoint joinPoint, Tool tool) throws Throwable {
        String toolName = resolveToolName(joinPoint, tool);
        ToolInvocationAuditService.StartedTool startedTool = auditService.start(toolName);
        try {
            Object result = joinPoint.proceed();
            auditService.success(startedTool, result instanceof String text ? text : null);
            return result;
        } catch (Throwable failure) {
            auditService.failure(startedTool, failure.getMessage());
            throw failure;
        }
    }

    private String resolveToolName(ProceedingJoinPoint joinPoint, Tool tool) {
        String configuredName = tool.name();
        if (configuredName != null && !configuredName.isBlank()) {
            return OpsMetricsService.safeTag(configuredName);
        }
        return OpsMetricsService.safeTag(joinPoint.getSignature().getName());
    }
}

package com.soarer.alert.dto.ops;

import java.util.List;
import java.util.UUID;

public record DiagnosisRunDetail(
        DiagnosisRunSummary run,
        List<AgentStepView> steps,
        List<ToolInvocationView> toolInvocations,
        List<EvidenceView> evidence,
        ReportView report
) {
}

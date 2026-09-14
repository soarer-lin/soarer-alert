package org.example.dto.ops;

import java.util.List;
import java.util.UUID;

public record DiagnosisTimelineResponse(
        UUID diagnosisRunId,
        List<AgentStepView> steps,
        List<ToolInvocationView> toolInvocations,
        List<EvidenceView> evidence
) {
}

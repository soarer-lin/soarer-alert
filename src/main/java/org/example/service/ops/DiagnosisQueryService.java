package org.example.service.ops;

import org.example.dto.ops.AgentStepView;
import org.example.dto.ops.AlertView;
import org.example.dto.ops.DiagnosisRunDetail;
import org.example.dto.ops.DiagnosisRunSummary;
import org.example.dto.ops.DiagnosisTimelineResponse;
import org.example.dto.ops.DocumentView;
import org.example.dto.ops.EvidenceView;
import org.example.dto.ops.OpsOverviewResponse;
import org.example.dto.ops.ReportView;
import org.example.dto.ops.ToolInvocationView;
import org.example.entity.OpsAgentStep;
import org.example.entity.OpsAlert;
import org.example.entity.OpsDiagnosisReport;
import org.example.entity.OpsDiagnosisRun;
import org.example.entity.OpsDocument;
import org.example.entity.OpsEvidence;
import org.example.entity.OpsToolInvocation;
import org.example.repository.OpsAgentStepRepository;
import org.example.repository.OpsAlertRepository;
import org.example.repository.OpsDiagnosisReportRepository;
import org.example.repository.OpsDiagnosisRunRepository;
import org.example.repository.OpsDocumentRepository;
import org.example.repository.OpsEvidenceRepository;
import org.example.repository.OpsToolInvocationRepository;
import org.example.service.persistence.DiagnosisPersistenceService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class DiagnosisQueryService {

    private static final int MAX_PAGE_SIZE = 100;

    private final OpsDiagnosisRunRepository runRepository;
    private final OpsAgentStepRepository stepRepository;
    private final OpsToolInvocationRepository toolInvocationRepository;
    private final OpsEvidenceRepository evidenceRepository;
    private final OpsDiagnosisReportRepository reportRepository;
    private final OpsAlertRepository alertRepository;
    private final OpsDocumentRepository documentRepository;

    public DiagnosisQueryService(
            OpsDiagnosisRunRepository runRepository,
            OpsAgentStepRepository stepRepository,
            OpsToolInvocationRepository toolInvocationRepository,
            OpsEvidenceRepository evidenceRepository,
            OpsDiagnosisReportRepository reportRepository,
            OpsAlertRepository alertRepository,
            OpsDocumentRepository documentRepository
    ) {
        this.runRepository = runRepository;
        this.stepRepository = stepRepository;
        this.toolInvocationRepository = toolInvocationRepository;
        this.evidenceRepository = evidenceRepository;
        this.reportRepository = reportRepository;
        this.alertRepository = alertRepository;
        this.documentRepository = documentRepository;
    }

    @Transactional(readOnly = true)
    public Page<DiagnosisRunSummary> listRuns(String status, int page, int size) {
        Pageable pageable = pageable(page, size, Sort.by(Sort.Direction.DESC, "startedAt"));
        Page<OpsDiagnosisRun> result;
        if (status == null || status.isBlank()) {
            result = runRepository.findAll(pageable);
        } else {
            result = runRepository.findByStatus(status.trim().toUpperCase(), pageable);
        }
        return result.map(this::toSummary);
    }

    @Transactional(readOnly = true)
    public DiagnosisRunSummary getRunSummary(UUID runId) {
        return toSummary(requireRun(runId));
    }

    @Transactional(readOnly = true)
    public DiagnosisRunDetail getRunDetail(UUID runId) {
        OpsDiagnosisRun run = requireRun(runId);
        return new DiagnosisRunDetail(
                toSummary(run),
                stepRepository.findByDiagnosisRunIdOrderByStepIndexAsc(runId)
                        .stream()
                        .map(this::toStep)
                        .toList(),
                toolInvocationRepository.findByDiagnosisRunIdOrderByStartedAtAsc(runId)
                        .stream()
                        .map(this::toToolInvocation)
                        .toList(),
                evidenceRepository.findByDiagnosisRunIdOrderByCreatedAtAsc(runId)
                        .stream()
                        .map(this::toEvidence)
                        .toList(),
                reportRepository.findByDiagnosisRunId(runId)
                        .map(this::toReport)
                        .orElse(null)
        );
    }

    @Transactional(readOnly = true)
    public DiagnosisTimelineResponse getTimeline(UUID runId) {
        requireRun(runId);
        return new DiagnosisTimelineResponse(
                runId,
                stepRepository.findByDiagnosisRunIdOrderByStepIndexAsc(runId)
                        .stream()
                        .map(this::toStep)
                        .toList(),
                toolInvocationRepository.findByDiagnosisRunIdOrderByStartedAtAsc(runId)
                        .stream()
                        .map(this::toToolInvocation)
                        .toList(),
                evidenceRepository.findByDiagnosisRunIdOrderByCreatedAtAsc(runId)
                        .stream()
                        .map(this::toEvidence)
                        .toList()
        );
    }

    @Transactional(readOnly = true)
    public ReportView getReport(UUID runId) {
        requireRun(runId);
        return reportRepository.findByDiagnosisRunId(runId)
                .map(this::toReport)
                .orElseThrow(() -> new ReportNotFoundException(runId));
    }

    @Transactional(readOnly = true)
    public Page<AlertView> listAlerts(String status, int page, int size) {
        Pageable pageable = pageable(page, size, Sort.by(Sort.Direction.DESC, "lastTriggeredAt"));
        Page<OpsAlert> result;
        if (status == null || status.isBlank()) {
            result = alertRepository.findAllByOrderByLastTriggeredAtDesc(pageable);
        } else {
            result = alertRepository.findByStatus(status.trim().toUpperCase(), pageable);
        }
        return result.map(this::toAlert);
    }

    @Transactional(readOnly = true)
    public Page<DocumentView> listDocuments(int page, int size, UUID currentUserId, boolean admin) {
        return documentRepository.findAllByOrderByCreatedAtDesc(pageable(page, size, Sort.unsorted()))
                .map(document -> toDocument(document, currentUserId, admin));
    }

    @Transactional(readOnly = true)
    public OpsOverviewResponse getOverview() {
        return new OpsOverviewResponse(
                runRepository.count(),
                runRepository.countByStatus(DiagnosisPersistenceService.STATUS_QUEUED),
                runRepository.countByStatus(DiagnosisPersistenceService.STATUS_RUNNING),
                runRepository.countByStatus(DiagnosisPersistenceService.STATUS_RETRYING),
                runRepository.countByStatus(DiagnosisPersistenceService.STATUS_SUCCESS),
                runRepository.countByStatus(DiagnosisPersistenceService.STATUS_FAILED),
                reportRepository.countByStatus(DiagnosisPersistenceService.STATUS_SUCCESS),
                alertRepository.count(),
                documentRepository.count()
        );
    }

    private OpsDiagnosisRun requireRun(UUID runId) {
        return runRepository.findById(runId)
                .orElseThrow(() -> new DiagnosisRunNotFoundException(runId));
    }

    private Pageable pageable(int page, int size, Sort sort) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return PageRequest.of(safePage, safeSize, sort);
    }

    private DiagnosisRunSummary toSummary(OpsDiagnosisRun run) {
        return new DiagnosisRunSummary(
                run.getId(),
                run.getRequestText(),
                run.getStatus(),
                run.getStartedAt(),
                run.getCompletedAt(),
                run.getDurationMs(),
                run.getErrorMessage()
        );
    }

    private AgentStepView toStep(OpsAgentStep step) {
        return new AgentStepView(
                step.getId(),
                step.getStepIndex(),
                step.getAgentName(),
                step.getStepType(),
                step.getInstruction(),
                step.getInputText(),
                step.getOutputText(),
                step.getStatus(),
                step.getStartedAt(),
                step.getCompletedAt(),
                step.getDurationMs(),
                step.getErrorMessage()
        );
    }

    private ToolInvocationView toToolInvocation(OpsToolInvocation invocation) {
        return new ToolInvocationView(
                invocation.getId(),
                invocation.getDiagnosisRunId(),
                invocation.getAgentStepId(),
                invocation.getToolName(),
                invocation.getStatus(),
                invocation.getStartedAt(),
                invocation.getCompletedAt(),
                invocation.getDurationMs(),
                invocation.getErrorMessage()
        );
    }

    private EvidenceView toEvidence(OpsEvidence evidence) {
        return new EvidenceView(
                evidence.getId(),
                evidence.getDiagnosisRunId(),
                evidence.getEvidenceType(),
                evidence.getSource(),
                evidence.getContent(),
                evidence.getCreatedAt()
        );
    }

    private ReportView toReport(OpsDiagnosisReport report) {
        return new ReportView(
                report.getId(),
                report.getDiagnosisRunId(),
                report.getContent(),
                report.getReportUrl(),
                report.getStatus(),
                report.getCreatedAt()
        );
    }

    private AlertView toAlert(OpsAlert alert) {
        return new AlertView(
                alert.getId(),
                alert.getDiagnosisRunId(),
                alert.getAlertName(),
                alert.getSeverity(),
                alert.getServiceName(),
                alert.getEnvironment(),
                alert.getStatus(),
                alert.getFirstTriggeredAt(),
                alert.getLastTriggeredAt(),
                alert.getCreatedAt()
        );
    }

    private DocumentView toDocument(OpsDocument document, UUID currentUserId, boolean admin) {
        boolean canDelete = admin || (currentUserId != null && currentUserId.equals(document.getCreatedBy()));
        return new DocumentView(
                document.getId(),
                document.getFileName(),
                document.getStorageKey(),
                document.getStorageUrl(),
                document.getContentHash(),
                document.getMediaType(),
                document.getByteSize(),
                document.getStatus(),
                document.getDocumentVersion(),
                document.getFailureReason(),
                document.getCreatedAt(),
                document.getUpdatedAt(),
                document.getIndexedAt(),
                document.getCreatedBy(),
                canDelete
        );
    }
}

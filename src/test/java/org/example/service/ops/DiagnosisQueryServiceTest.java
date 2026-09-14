package org.example.service.ops;

import org.example.dto.ops.DiagnosisRunDetail;
import org.example.dto.ops.DiagnosisRunSummary;
import org.example.dto.ops.DocumentView;
import org.example.dto.ops.ReportView;
import org.example.entity.OpsAgentStep;
import org.example.entity.OpsDiagnosisReport;
import org.example.entity.OpsDiagnosisRun;
import org.example.entity.OpsDocument;
import org.example.repository.OpsAgentStepRepository;
import org.example.repository.OpsAlertRepository;
import org.example.repository.OpsDiagnosisReportRepository;
import org.example.repository.OpsDiagnosisRunRepository;
import org.example.repository.OpsDocumentRepository;
import org.example.repository.OpsEvidenceRepository;
import org.example.repository.OpsToolInvocationRepository;
import org.example.service.persistence.DiagnosisPersistenceService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DiagnosisQueryServiceTest {

    private static final UUID RUN_ID = UUID.randomUUID();
    private static final UUID OPS_USER_ID = UUID.randomUUID();

    private final OpsDiagnosisRunRepository runRepository = mock(OpsDiagnosisRunRepository.class);
    private final OpsAgentStepRepository stepRepository = mock(OpsAgentStepRepository.class);
    private final OpsToolInvocationRepository toolInvocationRepository =
            mock(OpsToolInvocationRepository.class);
    private final OpsEvidenceRepository evidenceRepository = mock(OpsEvidenceRepository.class);
    private final OpsDiagnosisReportRepository reportRepository =
            mock(OpsDiagnosisReportRepository.class);
    private final OpsAlertRepository alertRepository = mock(OpsAlertRepository.class);
    private final OpsDocumentRepository documentRepository = mock(OpsDocumentRepository.class);
    private final DiagnosisQueryService service = new DiagnosisQueryService(
            runRepository,
            stepRepository,
            toolInvocationRepository,
            evidenceRepository,
            reportRepository,
            alertRepository,
            documentRepository
    );

    @Test
    void listRunsNormalizesStatusAndCapsPageSize() {
        Page<OpsDiagnosisRun> page = new PageImpl<>(
                List.of(run()),
                PageRequest.of(0, 100),
                1
        );
        when(runRepository.findByStatus(eq("RUNNING"), any(Pageable.class))).thenReturn(page);

        Page<DiagnosisRunSummary> result = service.listRuns(" running ", -2, 500);

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).id()).isEqualTo(RUN_ID);
        assertThat(result.getContent().get(0).status())
                .isEqualTo(DiagnosisPersistenceService.STATUS_RUNNING);
    }

    @Test
    void getRunDetailMapsRunStepsAndReport() {
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run()));
        when(stepRepository.findByDiagnosisRunIdOrderByStepIndexAsc(RUN_ID)).thenReturn(List.of(step()));
        when(toolInvocationRepository.findByDiagnosisRunIdOrderByStartedAtAsc(RUN_ID)).thenReturn(List.of());
        when(evidenceRepository.findByDiagnosisRunIdOrderByCreatedAtAsc(RUN_ID)).thenReturn(List.of());
        when(reportRepository.findByDiagnosisRunId(RUN_ID)).thenReturn(Optional.of(report()));

        DiagnosisRunDetail detail = service.getRunDetail(RUN_ID);

        assertThat(detail.run().id()).isEqualTo(RUN_ID);
        assertThat(detail.steps()).hasSize(1);
        assertThat(detail.steps().get(0).agentName()).isEqualTo("planner_agent");
        assertThat(detail.toolInvocations()).isEmpty();
        assertThat(detail.evidence()).isEmpty();
        assertThat(detail.report().content()).isEqualTo("final report");
    }

    @Test
    void getReportReturnsStoredReport() {
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run()));
        when(reportRepository.findByDiagnosisRunId(RUN_ID)).thenReturn(Optional.of(report()));

        ReportView report = service.getReport(RUN_ID);

        assertThat(report.diagnosisRunId()).isEqualTo(RUN_ID);
        assertThat(report.content()).isEqualTo("final report");
        assertThat(report.status()).isEqualTo(DiagnosisPersistenceService.STATUS_SUCCESS);
    }

    @Test
    void getReportThrowsNotFoundWhenReportIsMissing() {
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run()));
        when(reportRepository.findByDiagnosisRunId(RUN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getReport(RUN_ID))
                .isInstanceOf(ReportNotFoundException.class)
                .hasMessage("诊断报告不存在: " + RUN_ID);
    }

    @Test
    void listDocumentsMarksOnlyOwnedDocumentsDeletableForOpsUsers() {
        OpsDocument own = document(UUID.randomUUID(), OPS_USER_ID);
        OpsDocument adminDocument = document(UUID.randomUUID(), UUID.randomUUID());
        OpsDocument legacyDocument = document(UUID.randomUUID(), null);
        Page<OpsDocument> page = new PageImpl<>(List.of(own, adminDocument, legacyDocument));
        when(documentRepository.findAllByOrderByCreatedAtDesc(any(Pageable.class))).thenReturn(page);

        Page<DocumentView> result = service.listDocuments(0, 20, OPS_USER_ID, false);

        assertThat(result.getContent()).extracting(DocumentView::canDelete)
                .containsExactly(true, false, false);
    }

    @Test
    void listDocumentsMarksAllDocumentsDeletableForAdmins() {
        OpsDocument own = document(UUID.randomUUID(), UUID.randomUUID());
        OpsDocument legacyDocument = document(UUID.randomUUID(), null);
        Page<OpsDocument> page = new PageImpl<>(List.of(own, legacyDocument));
        when(documentRepository.findAllByOrderByCreatedAtDesc(any(Pageable.class))).thenReturn(page);

        Page<DocumentView> result = service.listDocuments(0, 20, OPS_USER_ID, true);

        assertThat(result.getContent()).extracting(DocumentView::canDelete)
                .containsOnly(true);
    }

    private OpsDiagnosisRun run() {
        OpsDiagnosisRun run = new OpsDiagnosisRun();
        run.setId(RUN_ID);
        run.setRequestText("diagnose CPU alert");
        run.setStatus(DiagnosisPersistenceService.STATUS_RUNNING);
        return run;
    }

    private OpsAgentStep step() {
        OpsAgentStep step = new OpsAgentStep();
        step.setId(UUID.randomUUID());
        step.setDiagnosisRunId(RUN_ID);
        step.setStepIndex(1);
        step.setAgentName("planner_agent");
        step.setStepType("PLANNING");
        step.setStatus(DiagnosisPersistenceService.STATUS_SUCCESS);
        return step;
    }

    private OpsDiagnosisReport report() {
        OpsDiagnosisReport report = new OpsDiagnosisReport();
        report.setId(UUID.randomUUID());
        report.setDiagnosisRunId(RUN_ID);
        report.setContent("final report");
        report.setStatus(DiagnosisPersistenceService.STATUS_SUCCESS);
        return report;
    }

    private OpsDocument document(UUID id, UUID createdBy) {
        OpsDocument document = new OpsDocument();
        document.setId(id);
        document.setFileName("runbook.md");
        document.setContentHash("hash-" + id);
        document.setMediaType("text/markdown");
        document.setByteSize(16L);
        document.setStatus(DiagnosisPersistenceService.STATUS_SUCCESS);
        document.setDocumentVersion(1);
        document.setCreatedBy(createdBy);
        return document;
    }
}

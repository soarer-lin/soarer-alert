package com.soarer.alert.service.persistence;

import com.soarer.alert.entity.OpsAgentStep;
import com.soarer.alert.entity.OpsDiagnosisReport;
import com.soarer.alert.entity.OpsDiagnosisRun;
import com.soarer.alert.repository.OpsAgentStepRepository;
import com.soarer.alert.repository.OpsDiagnosisReportRepository;
import com.soarer.alert.repository.OpsDiagnosisRunRepository;
import com.soarer.alert.service.cls.ClsLogEventUploader;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiagnosisPersistenceServiceTest {

    private final OpsDiagnosisRunRepository runRepository = mock(OpsDiagnosisRunRepository.class);
    private final OpsAgentStepRepository stepRepository = mock(OpsAgentStepRepository.class);
    private final OpsDiagnosisReportRepository reportRepository = mock(OpsDiagnosisReportRepository.class);
    private final DiagnosisPersistenceService service = new DiagnosisPersistenceService(
            runRepository,
            stepRepository,
            reportRepository
    );

    @Test
    void startRunUsesFallbackRequestTextAndRunningStatus() {
        when(runRepository.save(any(OpsDiagnosisRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OpsDiagnosisRun run = service.startRun(" ");

        assertThat(run.getRequestText()).isEqualTo("Automated AIOps alert diagnosis");
        assertThat(run.getStatus()).isEqualTo(DiagnosisPersistenceService.STATUS_RUNNING);
        assertThat(run.getStartedAt()).isNotNull();
    }

    @Test
    void queueRunUsesQueuedStatus() {
        when(runRepository.save(any(OpsDiagnosisRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OpsDiagnosisRun run = service.queueRun("Automated AIOps alert diagnosis");

        assertThat(run.getStatus()).isEqualTo(DiagnosisPersistenceService.STATUS_QUEUED);
        assertThat(run.getStartedAt()).isNotNull();
    }

    @Test
    void queueRunPublishesDiagnosisEventWhenUploaderExists() {
        ClsLogEventUploader uploader = mock(ClsLogEventUploader.class);
        DiagnosisPersistenceService serviceWithUploader = new DiagnosisPersistenceService(
                runRepository,
                stepRepository,
                reportRepository
        );
        ReflectionTestUtils.setField(serviceWithUploader, "clsLogEventUploader", uploader);
        UUID runId = UUID.randomUUID();
        when(runRepository.save(any(OpsDiagnosisRun.class))).thenAnswer(invocation -> {
            OpsDiagnosisRun savedRun = invocation.getArgument(0);
            savedRun.setId(runId);
            return savedRun;
        });

        OpsDiagnosisRun run = serviceWithUploader.queueRun("Diagnose CPU alert");

        verify(uploader).sendDiagnosisEvent(
                runId,
                DiagnosisPersistenceService.STATUS_QUEUED,
                "INFO",
                "Diagnosis run queued",
                Map.of()
        );
    }

    @Test
    void markRunRunningClearsPreviousError() {
        UUID runId = UUID.randomUUID();
        OpsDiagnosisRun run = run(runId);
        run.setStatus(DiagnosisPersistenceService.STATUS_RETRYING);
        run.setErrorMessage("previous failure");
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));

        service.markRunRunning(runId);

        assertThat(run.getStatus()).isEqualTo(DiagnosisPersistenceService.STATUS_RUNNING);
        assertThat(run.getErrorMessage()).isNull();
        verify(runRepository).save(run);
    }

    @Test
    void markRunRetryingStoresNextAttemptAndReason() {
        UUID runId = UUID.randomUUID();
        OpsDiagnosisRun run = run(runId);
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));

        service.markRunRetrying(runId, 2, "model timeout");

        assertThat(run.getStatus()).isEqualTo(DiagnosisPersistenceService.STATUS_RETRYING);
        assertThat(run.getErrorMessage()).isEqualTo("第2次尝试失败: model timeout");
        assertThat(run.getCompletedAt()).isNull();
        verify(runRepository).save(run);
    }

    @Test
    void getReportReturnsStoredDiagnosisReport() {
        UUID runId = UUID.randomUUID();
        OpsDiagnosisReport report = new OpsDiagnosisReport();
        report.setDiagnosisRunId(runId);
        report.setContent("final report");
        report.setStatus(DiagnosisPersistenceService.STATUS_SUCCESS);
        when(reportRepository.findByDiagnosisRunId(runId)).thenReturn(Optional.of(report));

        assertThat(service.getReport(runId)).contains(report);
    }

    @Test
    void startStepIncrementsStepIndexForEachRun() {
        UUID runId = UUID.randomUUID();
        when(stepRepository.findMaxStepIndexByDiagnosisRunId(runId)).thenReturn(Optional.of(2));
        when(stepRepository.save(any(OpsAgentStep.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OpsAgentStep step = service.startStep(
                runId,
                "planner_agent",
                "PLANNING",
                "Analyze the alert",
                "CPU alert"
        );

        assertThat(step.getDiagnosisRunId()).isEqualTo(runId);
        assertThat(step.getStepIndex()).isEqualTo(3);
        assertThat(step.getStatus()).isEqualTo(DiagnosisPersistenceService.STATUS_RUNNING);
    }

    @Test
    void completeStepStoresOutputAndSuccessState() {
        UUID stepId = UUID.randomUUID();
        OpsAgentStep step = step(stepId);
        when(stepRepository.findById(stepId)).thenReturn(Optional.of(step));

        service.completeStep(stepId, "final report");

        assertThat(step.getOutputText()).isEqualTo("final report");
        assertThat(step.getStatus()).isEqualTo(DiagnosisPersistenceService.STATUS_SUCCESS);
        assertThat(step.getCompletedAt()).isNotNull();
        assertThat(step.getDurationMs()).isNotNull();
        verify(stepRepository).save(step);
    }

    @Test
    void completeRunCreatesSuccessReport() {
        UUID runId = UUID.randomUUID();
        OpsDiagnosisRun run = run(runId);
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));
        when(reportRepository.findByDiagnosisRunId(runId)).thenReturn(Optional.empty());
        when(reportRepository.save(any(OpsDiagnosisReport.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.completeRun(runId, "final report");

        assertThat(run.getStatus()).isEqualTo(DiagnosisPersistenceService.STATUS_SUCCESS);
        assertThat(run.getErrorMessage()).isNull();
        ArgumentCaptor<OpsDiagnosisReport> reportCaptor = ArgumentCaptor.forClass(OpsDiagnosisReport.class);
        verify(reportRepository).save(reportCaptor.capture());
        assertThat(reportCaptor.getValue().getDiagnosisRunId()).isEqualTo(runId);
        assertThat(reportCaptor.getValue().getContent()).isEqualTo("final report");
        assertThat(reportCaptor.getValue().getStatus()).isEqualTo(DiagnosisPersistenceService.STATUS_SUCCESS);
    }

    @Test
    void failRunStoresErrorAndFailedState() {
        UUID runId = UUID.randomUUID();
        OpsDiagnosisRun run = run(runId);
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));

        service.failRun(runId, "tool unavailable");

        assertThat(run.getStatus()).isEqualTo(DiagnosisPersistenceService.STATUS_FAILED);
        assertThat(run.getErrorMessage()).isEqualTo("tool unavailable");
        assertThat(run.getCompletedAt()).isNotNull();
        verify(runRepository).save(run);
    }

    @Test
    void cancelRunStoresCanceledStateAndReason() {
        UUID runId = UUID.randomUUID();
        OpsDiagnosisRun run = run(runId);
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));

        service.cancelRun(runId, "Canceled by operator");

        assertThat(run.getStatus()).isEqualTo(DiagnosisPersistenceService.STATUS_CANCELED);
        assertThat(run.getErrorMessage()).isEqualTo("Canceled by operator");
        assertThat(run.getCompletedAt()).isNotNull();
        assertThat(run.getDurationMs()).isNotNull();
        verify(runRepository).save(run);
    }

    private OpsDiagnosisRun run(UUID id) {
        OpsDiagnosisRun run = new OpsDiagnosisRun();
        run.setId(id);
        run.setRequestText("diagnose CPU alert");
        run.setStatus(DiagnosisPersistenceService.STATUS_RUNNING);
        run.setStartedAt(LocalDateTime.now().minusSeconds(2));
        return run;
    }

    private OpsAgentStep step(UUID id) {
        OpsAgentStep step = new OpsAgentStep();
        step.setId(id);
        step.setDiagnosisRunId(UUID.randomUUID());
        step.setStepIndex(1);
        step.setAgentName("supervisor");
        step.setStepType("ORCHESTRATION");
        step.setStatus(DiagnosisPersistenceService.STATUS_RUNNING);
        step.setStartedAt(LocalDateTime.now().minusSeconds(2));
        return step;
    }
}

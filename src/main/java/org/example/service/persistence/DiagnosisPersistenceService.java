package org.example.service.persistence;

import org.example.entity.OpsAgentStep;
import org.example.entity.OpsDiagnosisReport;
import org.example.entity.OpsDiagnosisRun;
import org.example.repository.OpsAgentStepRepository;
import org.example.repository.OpsDiagnosisReportRepository;
import org.example.repository.OpsDiagnosisRunRepository;
import org.example.service.cls.ClsLogEventUploader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class DiagnosisPersistenceService {

    public static final String STATUS_QUEUED = "QUEUED";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_RETRYING = "RETRYING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELED = "CANCELED";

    private final OpsDiagnosisRunRepository runRepository;
    private final OpsAgentStepRepository stepRepository;
    private final OpsDiagnosisReportRepository reportRepository;

    @Autowired(required = false)
    private ClsLogEventUploader clsLogEventUploader;

    public DiagnosisPersistenceService(
            OpsDiagnosisRunRepository runRepository,
            OpsAgentStepRepository stepRepository,
            OpsDiagnosisReportRepository reportRepository
    ) {
        this.runRepository = runRepository;
        this.stepRepository = stepRepository;
        this.reportRepository = reportRepository;
    }

    @Transactional
    public OpsDiagnosisRun startRun(String requestText) {
        OpsDiagnosisRun run = createRun(requestText, STATUS_RUNNING, null);
        publishDiagnosisEvent(run.getId(), STATUS_RUNNING, "INFO", "Diagnosis run started");
        return run;
    }

    @Transactional
    public OpsDiagnosisRun queueRun(String requestText) {
        return queueRun(requestText, null);
    }

    @Transactional
    public OpsDiagnosisRun queueRun(String requestText, UUID createdBy) {
        OpsDiagnosisRun run = createRun(requestText, STATUS_QUEUED, createdBy);
        publishDiagnosisEvent(run.getId(), STATUS_QUEUED, "INFO", "Diagnosis run queued");
        return run;
    }

    @Transactional
    public void markRunRunning(UUID runId) {
        OpsDiagnosisRun run = requireRun(runId);
        run.setStatus(STATUS_RUNNING);
        run.setErrorMessage(null);
        runRepository.save(run);
        publishDiagnosisEvent(runId, STATUS_RUNNING, "INFO", "Diagnosis run started");
    }

    @Transactional
    public void markRunRetrying(UUID runId, int nextAttempt, String failureReason) {
        OpsDiagnosisRun run = requireRun(runId);
        run.setStatus(STATUS_RETRYING);
        run.setErrorMessage("第" + nextAttempt + "次尝试失败: " + failureReason);
        runRepository.save(run);
        publishDiagnosisEvent(
                runId,
                STATUS_RETRYING,
                "WARN",
                run.getErrorMessage(),
                Map.of("attempt", Integer.toString(nextAttempt))
        );
    }

    @Transactional(readOnly = true)
    public Optional<OpsDiagnosisReport> getReport(UUID runId) {
        return reportRepository.findByDiagnosisRunId(runId);
    }

    private OpsDiagnosisRun createRun(String requestText, String status, UUID createdBy) {
        OpsDiagnosisRun run = new OpsDiagnosisRun();
        run.setRequestText(requestText == null || requestText.isBlank()
                ? "Automated AIOps alert diagnosis"
                : requestText);
        run.setStatus(status);
        run.setStartedAt(LocalDateTime.now());
        run.setCreatedBy(createdBy);
        return runRepository.save(run);
    }

    @Transactional
    public OpsAgentStep startStep(
            UUID diagnosisRunId,
            String agentName,
            String stepType,
            String instruction,
            String inputText
    ) {
        int nextStepIndex = stepRepository.findMaxStepIndexByDiagnosisRunId(diagnosisRunId)
                .orElse(0) + 1;
        OpsAgentStep step = new OpsAgentStep();
        step.setDiagnosisRunId(diagnosisRunId);
        step.setStepIndex(nextStepIndex);
        step.setAgentName(agentName);
        step.setStepType(stepType);
        step.setInstruction(instruction);
        step.setInputText(inputText);
        step.setStatus(STATUS_RUNNING);
        step.setStartedAt(LocalDateTime.now());
        OpsAgentStep savedStep = stepRepository.save(step);
        publishDiagnosisEvent(
                diagnosisRunId,
                STATUS_RUNNING,
                "INFO",
                "Agent step started",
                Map.of(
                        "agent_name", savedStep.getAgentName(),
                        "step_type", savedStep.getStepType(),
                        "step_index", Integer.toString(savedStep.getStepIndex())
                )
        );
        return savedStep;
    }

    @Transactional
    public void completeStep(UUID stepId, String outputText) {
        OpsAgentStep step = stepRepository.findById(stepId)
                .orElseThrow(() -> new IllegalArgumentException("Agent 步骤不存在: " + stepId));
        step.setOutputText(outputText);
        step.complete(STATUS_SUCCESS);
        stepRepository.save(step);
        publishDiagnosisEvent(
                step.getDiagnosisRunId(),
                STATUS_SUCCESS,
                "INFO",
                "Agent step completed",
                Map.of(
                        "agent_name", step.getAgentName(),
                        "step_type", step.getStepType(),
                        "step_index", Integer.toString(step.getStepIndex()),
                        "output_length", Integer.toString(outputText == null ? 0 : outputText.length())
                )
        );
    }

    @Transactional
    public void failStep(UUID stepId, String errorMessage) {
        OpsAgentStep step = stepRepository.findById(stepId)
                .orElseThrow(() -> new IllegalArgumentException("Agent 步骤不存在: " + stepId));
        step.setErrorMessage(errorMessage);
        step.complete(STATUS_FAILED);
        stepRepository.save(step);
        publishDiagnosisEvent(
                step.getDiagnosisRunId(),
                STATUS_FAILED,
                "ERROR",
                errorMessage,
                Map.of(
                        "agent_name", step.getAgentName(),
                        "step_type", step.getStepType(),
                        "step_index", Integer.toString(step.getStepIndex())
                )
        );
    }

    @Transactional
    public void completeRun(UUID runId, String reportText) {
        OpsDiagnosisRun run = requireRun(runId);
        run.complete(STATUS_SUCCESS);
        run.setErrorMessage(null);
        runRepository.save(run);

        OpsDiagnosisReport report = reportRepository.findByDiagnosisRunId(runId)
                .orElseGet(OpsDiagnosisReport::new);
        report.setDiagnosisRunId(runId);
        report.setContent(reportText == null ? "" : reportText);
        report.setStatus(STATUS_SUCCESS);
        reportRepository.save(report);
        publishDiagnosisEvent(
                runId,
                STATUS_SUCCESS,
                "INFO",
                "Diagnosis run succeeded",
                Map.of("report_length", Integer.toString(report.getContent().length()))
        );
    }

    @Transactional
    public void failRun(UUID runId, String errorMessage) {
        OpsDiagnosisRun run = requireRun(runId);
        run.complete(STATUS_FAILED);
        run.setErrorMessage(errorMessage);
        runRepository.save(run);
        publishDiagnosisEvent(runId, STATUS_FAILED, "ERROR", errorMessage);
    }

    @Transactional
    public void cancelRun(UUID runId, String reason) {
        OpsDiagnosisRun run = requireRun(runId);
        run.complete(STATUS_CANCELED);
        run.setErrorMessage(reason);
        runRepository.save(run);
        publishDiagnosisEvent(runId, STATUS_CANCELED, "WARN", reason);
    }

    @Transactional(readOnly = true)
    public Optional<OpsDiagnosisRun> getRun(UUID runId) {
        return runRepository.findById(runId);
    }

    private OpsDiagnosisRun requireRun(UUID runId) {
        return runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("诊断任务不存在: " + runId));
    }

    private void publishDiagnosisEvent(
            UUID diagnosisRunId,
            String status,
            String level,
            String message
    ) {
        publishDiagnosisEvent(diagnosisRunId, status, level, message, Map.of());
    }

    private void publishDiagnosisEvent(
            UUID diagnosisRunId,
            String status,
            String level,
            String message,
            Map<String, String> extraFields
    ) {
        if (clsLogEventUploader == null || diagnosisRunId == null) {
            return;
        }
        publishAfterCommit(() -> clsLogEventUploader.sendDiagnosisEvent(
                diagnosisRunId,
                status,
                level,
                message,
                extraFields
        ));
    }

    private void publishAfterCommit(Runnable eventPublisher) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            eventPublisher.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                eventPublisher.run();
            }
        });
    }
}

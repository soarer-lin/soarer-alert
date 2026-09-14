package org.example.service.ops;

import org.example.dto.ops.DiagnosisControlResult;
import org.example.entity.OpsDiagnosisRun;
import org.example.service.persistence.DiagnosisPersistenceService;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class DiagnosisControlService {

    private final DiagnosisPersistenceService persistenceService;

    public DiagnosisControlService(DiagnosisPersistenceService persistenceService) {
        this.persistenceService = persistenceService;
    }

    public DiagnosisControlResult cancel(UUID runId) {
        OpsDiagnosisRun run = persistenceService.getRun(runId)
                .orElseThrow(() -> new DiagnosisRunNotFoundException(runId));
        if (isTerminal(run.getStatus())) {
            return DiagnosisControlResult.rejected(
                    runId,
                    "cancel",
                    run.getStatus(),
                    "任务已结束，不能取消"
            );
        }

        persistenceService.cancelRun(runId, "Canceled by operator");
        return DiagnosisControlResult.accepted(
                runId,
                "cancel",
                DiagnosisPersistenceService.STATUS_CANCELED
        );
    }

    public DiagnosisControlResult unsupportedControl(UUID runId, String command) {
        OpsDiagnosisRun run = persistenceService.getRun(runId)
                .orElseThrow(() -> new DiagnosisRunNotFoundException(runId));
        return DiagnosisControlResult.unsupported(runId, command, run.getStatus());
    }

    private boolean isTerminal(String status) {
        return DiagnosisPersistenceService.STATUS_SUCCESS.equals(status)
                || DiagnosisPersistenceService.STATUS_FAILED.equals(status)
                || DiagnosisPersistenceService.STATUS_CANCELED.equals(status);
    }
}

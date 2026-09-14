package org.example.service.ops;

import org.example.dto.ops.DiagnosisControlResult;
import org.example.entity.OpsDiagnosisRun;
import org.example.service.persistence.DiagnosisPersistenceService;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiagnosisControlServiceTest {

    private static final UUID RUN_ID = UUID.randomUUID();

    private final DiagnosisPersistenceService persistenceService = mock(DiagnosisPersistenceService.class);
    private final DiagnosisControlService service = new DiagnosisControlService(persistenceService);

    @Test
    void cancelMarksNonTerminalRunCanceled() {
        when(persistenceService.getRun(RUN_ID))
                .thenReturn(Optional.of(run(DiagnosisPersistenceService.STATUS_RUNNING)));

        DiagnosisControlResult result = service.cancel(RUN_ID);

        assertThat(result.runId()).isEqualTo(RUN_ID);
        assertThat(result.command()).isEqualTo("cancel");
        assertThat(result.result()).isEqualTo("ACCEPTED");
        assertThat(result.status()).isEqualTo(DiagnosisPersistenceService.STATUS_CANCELED);
        verify(persistenceService).cancelRun(RUN_ID, "Canceled by operator");
    }

    @Test
    void cancelRejectsTerminalRunWithoutChangingState() {
        when(persistenceService.getRun(RUN_ID))
                .thenReturn(Optional.of(run(DiagnosisPersistenceService.STATUS_SUCCESS)));

        DiagnosisControlResult result = service.cancel(RUN_ID);

        assertThat(result.result()).isEqualTo("REJECTED");
        assertThat(result.status()).isEqualTo(DiagnosisPersistenceService.STATUS_SUCCESS);
        assertThat(result.message()).isEqualTo("任务已结束，不能取消");
        verify(persistenceService, never()).cancelRun(RUN_ID, "Canceled by operator");
    }

    @Test
    void pauseAndResumeAreExplicitlyUnsupported() {
        when(persistenceService.getRun(RUN_ID))
                .thenReturn(Optional.of(run(DiagnosisPersistenceService.STATUS_RUNNING)));

        DiagnosisControlResult result = service.unsupportedControl(RUN_ID, "pause");

        assertThat(result.result()).isEqualTo("UNSUPPORTED");
        assertThat(result.command()).isEqualTo("pause");
        assertThat(result.message()).contains("pause/resume");
        verify(persistenceService, never()).cancelRun(RUN_ID, "Canceled by operator");
    }

    @Test
    void missingRunThrowsNotFound() {
        when(persistenceService.getRun(RUN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancel(RUN_ID))
                .isInstanceOf(DiagnosisRunNotFoundException.class)
                .hasMessage("诊断任务不存在: " + RUN_ID);
    }

    private OpsDiagnosisRun run(String status) {
        OpsDiagnosisRun run = new OpsDiagnosisRun();
        run.setId(RUN_ID);
        run.setRequestText("diagnose CPU alert");
        run.setStatus(status);
        return run;
    }
}

package com.soarer.alert.service.ops;

import com.soarer.alert.entity.OpsDiagnosisRun;
import com.soarer.alert.service.auth.AiQuotaExhaustedException;
import com.soarer.alert.service.auth.AiQuotaService;
import com.soarer.alert.service.persistence.DiagnosisPersistenceService;
import com.soarer.alert.service.stream.DiagnosisRunStreamPublisher;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiagnosisRunServiceTest {

    private final DiagnosisPersistenceService persistenceService = mock(DiagnosisPersistenceService.class);
    private final DiagnosisRunStreamPublisher streamPublisher = mock(DiagnosisRunStreamPublisher.class);
    private final AiQuotaService aiQuotaService = mock(AiQuotaService.class);
    private final DiagnosisRunService service =
            new DiagnosisRunService(persistenceService, streamPublisher, aiQuotaService);

    @Test
    void queueRunTrimsRequestTextAndEnqueuesStreamTask() {
        UUID runId = UUID.randomUUID();
        when(persistenceService.queueRun("diagnose CPU alert", null)).thenReturn(run(runId));

        OpsDiagnosisRun result = service.queueRun("  diagnose CPU alert  ");

        assertThat(result.getId()).isEqualTo(runId);
        verify(streamPublisher).enqueue(runId);
    }

    @Test
    void queueRunUsesDefaultRequestTextWhenInputIsBlank() {
        UUID runId = UUID.randomUUID();
        when(persistenceService.queueRun(DiagnosisRunService.DEFAULT_REQUEST_TEXT, null)).thenReturn(run(runId));

        OpsDiagnosisRun result = service.queueRun("   ");

        assertThat(result.getId()).isEqualTo(runId);
        verify(streamPublisher).enqueue(runId);
    }

    @Test
    void queueRunMarksRunFailedWhenStreamIsUnavailable() {
        UUID runId = UUID.randomUUID();
        when(persistenceService.queueRun(DiagnosisRunService.DEFAULT_REQUEST_TEXT, null)).thenReturn(run(runId));
        when(streamPublisher.enqueue(runId)).thenThrow(new RuntimeException("Redis unavailable"));

        assertThatThrownBy(() -> service.queueRun(null))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Redis unavailable");

        verify(persistenceService).failRun(runId, "java.lang.RuntimeException: Redis unavailable");
        verify(aiQuotaService).refund(null);
    }

    @Test
    void queueRunPropagatesQuotaExhausted() {
        doThrow(new AiQuotaExhaustedException()).when(aiQuotaService).consume(null);

        assertThatThrownBy(() -> service.queueRun(null))
                .isInstanceOf(AiQuotaExhaustedException.class)
                .hasMessage("额度已耗尽，请联系管理员重置~");

        verify(persistenceService, never()).queueRun(any(), any());
        verify(streamPublisher, never()).enqueue(any());
    }

    private OpsDiagnosisRun run(UUID id) {
        OpsDiagnosisRun run = new OpsDiagnosisRun();
        run.setId(id);
        run.setRequestText(DiagnosisRunService.DEFAULT_REQUEST_TEXT);
        run.setStatus(DiagnosisPersistenceService.STATUS_QUEUED);
        return run;
    }
}

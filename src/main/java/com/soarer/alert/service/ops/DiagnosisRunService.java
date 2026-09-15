package com.soarer.alert.service.ops;

import com.soarer.alert.entity.OpsDiagnosisRun;
import com.soarer.alert.service.auth.AiQuotaService;
import com.soarer.alert.service.persistence.DiagnosisPersistenceService;
import com.soarer.alert.service.stream.DiagnosisRunStreamPublisher;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * 诊断任务编排服务。
 */
@Service
public class DiagnosisRunService {

    public static final String DEFAULT_REQUEST_TEXT = "Automated AIOps alert diagnosis";

    private final DiagnosisPersistenceService persistenceService;
    private final DiagnosisRunStreamPublisher streamPublisher;
    private final AiQuotaService aiQuotaService;

    public DiagnosisRunService(
            DiagnosisPersistenceService persistenceService,
            DiagnosisRunStreamPublisher streamPublisher,
            AiQuotaService aiQuotaService
    ) {
        this.persistenceService = persistenceService;
        this.streamPublisher = streamPublisher;
        this.aiQuotaService = aiQuotaService;
    }

    public OpsDiagnosisRun queueRun(String requestText) {
        return queueRun(requestText, null);
    }

    public OpsDiagnosisRun queueRun(String requestText, java.util.UUID createdBy) {
        String normalizedRequest = requestText == null || requestText.isBlank()
                ? DEFAULT_REQUEST_TEXT
                : requestText.trim();
        aiQuotaService.consume(createdBy);
        OpsDiagnosisRun run = null;
        try {
            run = persistenceService.queueRun(normalizedRequest, createdBy);
            MDC.put("diagnosisRunId", run.getId().toString());
            streamPublisher.enqueue(run.getId());
            return run;
        } catch (Exception e) {
            if (run != null) {
                persistenceService.failRun(run.getId(), e.toString());
            }
            aiQuotaService.refund(createdBy);
            throw e;
        } finally {
            if (run != null) {
                MDC.remove("diagnosisRunId");
            }
        }
    }
}

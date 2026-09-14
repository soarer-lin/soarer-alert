package com.soarer.alert.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.soarer.alert.dto.ops.DiagnosisControlResult;
import com.soarer.alert.entity.OpsDiagnosisRun;
import com.soarer.alert.service.ops.DiagnosisControlService;
import com.soarer.alert.service.ops.DiagnosisQueryService;
import com.soarer.alert.service.ops.DiagnosisRunService;
import com.soarer.alert.service.ops.ReportNotFoundException;
import com.soarer.alert.service.auth.AiQuotaExhaustedException;
import com.soarer.alert.service.auth.AuthUserService;
import com.soarer.alert.service.persistence.DiagnosisPersistenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OpsControllerTest {

    private static final UUID RUN_ID = UUID.randomUUID();

    private final DiagnosisQueryService queryService = mock(DiagnosisQueryService.class);
    private final DiagnosisRunService runService = mock(DiagnosisRunService.class);
    private final DiagnosisControlService controlService = mock(DiagnosisControlService.class);
    private final AuthUserService authUserService = mock(AuthUserService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        OpsController controller = new OpsController(queryService, runService, controlService, authUserService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new OpsControllerAdvice())
                .build();
    }

    @Test
    void createRunReturnsAcceptedResponse() throws Exception {
        when(runService.queueRun("diagnose CPU alert", null)).thenReturn(run());

        mockMvc.perform(post("/api/ai_ops/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new OpsController.CreateDiagnosisRunRequest("diagnose CPU alert")
                        )))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(RUN_ID.toString()))
                .andExpect(jsonPath("$.data.status").value("QUEUED"));
    }

    @Test
    void createRunReturns429WhenQuotaIsExhausted() throws Exception {
        when(runService.queueRun("diagnose CPU alert", null))
                .thenThrow(new AiQuotaExhaustedException());

        mockMvc.perform(post("/api/ai_ops/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new OpsController.CreateDiagnosisRunRequest("diagnose CPU alert")
                        )))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(429))
                .andExpect(jsonPath("$.message").value("额度已耗尽，请联系管理员重置~"));
    }

    @Test
    void missingReportReturnsApi404() throws Exception {
        when(queryService.getReport(RUN_ID)).thenThrow(new ReportNotFoundException(RUN_ID));

        mockMvc.perform(get("/api/ai_ops/runs/{runId}/report", RUN_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("诊断报告不存在: " + RUN_ID));
    }

    @Test
    void cancelRunDelegatesToControlService() throws Exception {
        when(controlService.cancel(RUN_ID)).thenReturn(DiagnosisControlResult.accepted(
                RUN_ID,
                "cancel",
                DiagnosisPersistenceService.STATUS_CANCELED
        ));

        mockMvc.perform(post("/api/ai_ops/runs/{runId}/cancel", RUN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.status").value("CANCELED"));
    }

    private OpsDiagnosisRun run() {
        OpsDiagnosisRun run = new OpsDiagnosisRun();
        run.setId(RUN_ID);
        run.setRequestText("diagnose CPU alert");
        run.setStatus(DiagnosisPersistenceService.STATUS_QUEUED);
        return run;
    }
}

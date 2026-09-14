package com.soarer.alert.controller;

import com.soarer.alert.dto.ops.AlertView;
import com.soarer.alert.dto.ops.ApiResult;
import com.soarer.alert.dto.ops.DiagnosisControlResult;
import com.soarer.alert.dto.ops.DiagnosisRunDetail;
import com.soarer.alert.dto.ops.DiagnosisRunSummary;
import com.soarer.alert.dto.ops.DiagnosisTimelineResponse;
import com.soarer.alert.dto.ops.DocumentView;
import com.soarer.alert.dto.ops.OpsOverviewResponse;
import com.soarer.alert.dto.ops.PageResponse;
import com.soarer.alert.dto.ops.ReportView;
import com.soarer.alert.entity.AuthUser;
import com.soarer.alert.service.ops.DiagnosisQueryService;
import com.soarer.alert.service.ops.DiagnosisRunService;
import com.soarer.alert.service.ops.DiagnosisControlService;
import com.soarer.alert.service.auth.AuthUserService;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class OpsController {

    private final DiagnosisQueryService queryService;
    private final DiagnosisRunService runService;
    private final DiagnosisControlService controlService;
    private final AuthUserService authUserService;

    public OpsController(
            DiagnosisQueryService queryService,
            DiagnosisRunService runService,
            DiagnosisControlService controlService,
            AuthUserService authUserService
    ) {
        this.queryService = queryService;
        this.runService = runService;
        this.controlService = controlService;
        this.authUserService = authUserService;
    }

    @GetMapping("/api/ai_ops/overview")
    public ApiResult<OpsOverviewResponse> overview() {
        return ApiResult.success(queryService.getOverview());
    }

    @GetMapping("/api/ai_ops/runs")
    public ApiResult<PageResponse<DiagnosisRunSummary>> runs(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Page<DiagnosisRunSummary> result = queryService.listRuns(status, page, size);
        return ApiResult.success(PageResponse.from(result));
    }

    @PostMapping("/api/ai_ops/runs")
    public ResponseEntity<ApiResult<DiagnosisRunSummary>> createRun(
            @RequestBody(required = false) CreateDiagnosisRunRequest request
    ) {
        String requestText = request == null ? null : request.requestText();
        java.util.UUID createdBy = authUserService.currentUserId().orElse(null);
        DiagnosisRunSummary run = toSummary(runService.queueRun(requestText, createdBy));
        return ResponseEntity.accepted().body(ApiResult.success(run));
    }

    @GetMapping("/api/ai_ops/runs/{runId}")
    public ApiResult<DiagnosisRunDetail> runDetail(@PathVariable UUID runId) {
        return ApiResult.success(queryService.getRunDetail(runId));
    }

    @GetMapping("/api/ai_ops/runs/{runId}/report")
    public ApiResult<ReportView> report(@PathVariable UUID runId) {
        return ApiResult.success(queryService.getReport(runId));
    }

    @GetMapping("/api/ai_ops/runs/{runId}/timeline")
    public ApiResult<DiagnosisTimelineResponse> timeline(@PathVariable UUID runId) {
        return ApiResult.success(queryService.getTimeline(runId));
    }

    @PostMapping("/api/ai_ops/runs/{runId}/cancel")
    public ApiResult<DiagnosisControlResult> cancelRun(@PathVariable UUID runId) {
        return ApiResult.success(controlService.cancel(runId));
    }

    @GetMapping("/api/ai_ops/alerts")
    public ApiResult<PageResponse<AlertView>> alerts(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Page<AlertView> result = queryService.listAlerts(status, page, size);
        return ApiResult.success(PageResponse.from(result));
    }

    @GetMapping("/api/documents")
    public ApiResult<PageResponse<DocumentView>> documents(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        AuthUser currentUser = authUserService.currentUser()
                .orElseThrow(() -> new IllegalStateException("当前登录用户不存在"));
        Page<DocumentView> result = queryService.listDocuments(
                page,
                size,
                currentUser.getId(),
                AuthUser.ROLE_ADMIN.equals(currentUser.getRole())
        );
        return ApiResult.success(PageResponse.from(result));
    }

    private DiagnosisRunSummary toSummary(com.soarer.alert.entity.OpsDiagnosisRun run) {
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

    public record CreateDiagnosisRunRequest(String requestText) {
    }
}

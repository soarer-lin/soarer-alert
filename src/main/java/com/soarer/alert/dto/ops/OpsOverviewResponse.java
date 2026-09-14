package com.soarer.alert.dto.ops;

public record OpsOverviewResponse(
        long totalRuns,
        long queuedRuns,
        long runningRuns,
        long retryingRuns,
        long successfulRuns,
        long failedRuns,
        long successfulReports,
        long alerts,
        long documents
) {
}

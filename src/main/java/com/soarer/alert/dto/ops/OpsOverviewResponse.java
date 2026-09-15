package com.soarer.alert.dto.ops;

/**
 * OpsOverviewResponse 数据传输对象。
 */
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

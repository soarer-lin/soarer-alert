package com.soarer.alert.service.ops;

import java.util.UUID;

public class ReportNotFoundException extends RuntimeException {

    public ReportNotFoundException(UUID runId) {
        super("诊断报告不存在: " + runId);
    }
}

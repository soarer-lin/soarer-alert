package com.soarer.alert.service.ops;

import java.util.UUID;

public class DiagnosisRunNotFoundException extends RuntimeException {

    public DiagnosisRunNotFoundException(UUID runId) {
        super("诊断任务不存在: " + runId);
    }
}

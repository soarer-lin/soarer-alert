package com.soarer.alert.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;
import com.soarer.alert.service.cls.ClsLogEventUploader;

import java.util.LinkedHashMap;
import java.util.Map;

public class ClsLogbackAppender extends AppenderBase<ILoggingEvent> {

    private final ClsLogEventUploader uploader;

    public ClsLogbackAppender(ClsLogEventUploader uploader) {
        this.uploader = uploader;
        setName("TENCENT_CLS");
    }

    public boolean safeStart() {
        if (!uploader.isAvailable()) {
            addWarn("Tencent CLS uploader is unavailable: " + uploader.getDisabledReason());
            return false;
        }
        start();
        return isStarted();
    }

    @Override
    protected void append(ILoggingEvent event) {
        String loggerName = event.getLoggerName();
        if (loggerName == null
                || loggerName.startsWith("com.tencentcloudapi.cls")
                || loggerName.equals(ClsLogEventUploader.class.getName())
                || loggerName.equals(ClsLogbackAppender.class.getName())) {
            return;
        }

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("level", String.valueOf(event.getLevel()));
        fields.put("logger", loggerName);
        fields.put("thread", event.getThreadName());
        fields.put("message", event.getFormattedMessage());
        putIfPresent(fields, "trace_id", event.getMDCPropertyMap().get("traceId"));
        putIfPresent(fields, "session_id", event.getMDCPropertyMap().get("sessionId"));
        putIfPresent(fields, "diagnosis_run_id", event.getMDCPropertyMap().get("diagnosisRunId"));
        putIfPresent(fields, "agent_step_id", event.getMDCPropertyMap().get("agentStepId"));
        putIfPresent(fields, "document_id", event.getMDCPropertyMap().get("documentId"));
        putIfPresent(fields, "attempt", event.getMDCPropertyMap().get("attempt"));
        if (event.getThrowableProxy() != null) {
            fields.put("stack_trace", ThrowableProxyUtil.asString(event.getThrowableProxy()));
        }

        uploader.sendAppLog(event.getTimeStamp(), fields);
    }

    private static void putIfPresent(Map<String, String> fields, String key, String value) {
        if (value != null && !value.isBlank()) {
            fields.put(key, value);
        }
    }
}

package org.example.service.cls;

import com.tencentcloudapi.cls.producer.AsyncProducerClient;
import com.tencentcloudapi.cls.producer.AsyncProducerConfig;
import com.tencentcloudapi.cls.producer.Result;
import com.tencentcloudapi.cls.producer.common.LogItem;
import org.example.config.ClsUploadProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Service
@ConditionalOnProperty(prefix = "cls.upload", name = "enabled", havingValue = "true")
public class ClsLogEventUploader {

    private static final Logger logger = LoggerFactory.getLogger(ClsLogEventUploader.class);

    private final ClsUploadProperties properties;
    private final AsyncProducerClient client;
    private final boolean available;
    private final String disabledReason;
    private final AtomicReference<String> lastFailure = new AtomicReference<>();

    public ClsLogEventUploader(ClsUploadProperties properties) {
        this.properties = properties;

        if (!properties.hasCredentials()) {
            this.client = null;
            this.available = false;
            this.disabledReason = "CLS writer credentials are missing";
        } else if (!properties.hasTopics()) {
            this.client = null;
            this.available = false;
            this.disabledReason = "CLS app and diagnosis topic IDs are required";
        } else {
            AsyncProducerClient created = null;
            String reason = null;
            try {
                created = new AsyncProducerClient(createProducerConfig());
            } catch (Exception exception) {
                reason = exception.toString();
            }
            this.client = created;
            this.available = created != null;
            this.disabledReason = reason;
        }

        if (available) {
            logger.info("Tencent CLS log upload enabled for region {}", properties.getRegion());
        } else {
            logger.warn("Tencent CLS log upload disabled: {}", disabledReason);
        }
    }

    public boolean isAvailable() {
        return available;
    }

    public String getDisabledReason() {
        return disabledReason;
    }

    public String getLastFailure() {
        return lastFailure.get();
    }

    public void sendAppLog(long timestampMillis, Map<String, String> eventFields) {
        Map<String, String> fields = commonFields("springboot");
        fields.put("timestamp", Instant.ofEpochMilli(timestampMillis).toString());
        eventFields.forEach((key, value) -> {
            if (value != null && !value.isBlank()) {
                fields.put(key, value);
            }
        });
        put(properties.getTopics().getApp(), fields, timestampMillis);
    }

    public void sendDiagnosisEvent(
            UUID taskId,
            String status,
            String level,
            String message,
            Map<String, String> extraFields
    ) {
        Map<String, String> fields = commonFields("diagnosis");
        fields.put("task_id", taskId == null ? "unknown" : taskId.toString());
        fields.put("diagnosis_run_id", taskId == null ? "unknown" : taskId.toString());
        fields.put("status", status == null ? "UNKNOWN" : status);
        fields.put("level", level == null ? "INFO" : level);
        fields.put("message", message == null ? "" : message);
        putIfPresent(fields, "trace_id", MDC.get("traceId"));
        extraFields.forEach((key, value) -> {
            if (value != null && !value.isBlank()) {
                fields.put(key, value);
            }
        });
        put(properties.getTopics().getDiagnosis(), fields, System.currentTimeMillis());
    }

    private Map<String, String> commonFields(String logType) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("service", properties.getServiceName());
        fields.put("environment", properties.getEnvironment());
        fields.put("host_name", properties.resolvedHostName());
        fields.put("ip", properties.resolvedIp());
        fields.put("port", properties.getPort());
        fields.put("instance_id", properties.instanceId());
        fields.put("log_type", logType);
        return fields;
    }

    private void put(String topicId, Map<String, String> fields, long timestampMillis) {
        if (!available || topicId == null || topicId.isBlank() || fields.isEmpty()) {
            return;
        }

        LogItem item = new LogItem(timestampMillis / 1000);
        fields.forEach(item::PushBack);
        try {
            client.putLogs(topicId, List.of(item), result -> {
                if (result.isSuccessful()) {
                    lastFailure.compareAndSet(lastFailure.get(), null);
                } else {
                    lastFailure.set("CLS upload failed: " + result.getErrorMessage());
                }
            });
        } catch (Exception exception) {
            lastFailure.set("CLS upload failed: " + exception);
        }
    }

    private AsyncProducerConfig createProducerConfig() {
        AsyncProducerConfig config = new AsyncProducerConfig(
                properties.resolvedEndpoint(),
                properties.getSecretId(),
                properties.getSecretKey(),
                properties.resolvedIp()
        );
        config.setSendThreadCount(properties.getSendThreadCount());
        config.setTotalSizeInBytes(properties.getTotalSizeInBytes());
        config.setMaxBlockMs(properties.getMaxBlockMs());
        config.setBatchCountThreshold(properties.getBatchCountThreshold());
        config.setLingerMs(properties.getLingerMs());
        config.setRetries(properties.getRetries());
        config.setBaseRetryBackoffMs(properties.getBaseRetryBackoffMs());
        config.setMaxRetryBackoffMs(properties.getMaxRetryBackoffMs());
        return config;
    }

    private static void putIfPresent(Map<String, String> fields, String key, String value) {
        if (value != null && !value.isBlank()) {
            fields.put(key, value);
        }
    }

    @PreDestroy
    public void close() {
        if (client == null) {
            return;
        }
        try {
            client.close(5000);
        } catch (Exception exception) {
            logger.warn("Failed to close Tencent CLS producer: {}", exception.toString());
        }
    }
}

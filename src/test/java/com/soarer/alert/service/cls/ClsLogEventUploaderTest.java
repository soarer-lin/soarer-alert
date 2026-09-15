package com.soarer.alert.service.cls;

import com.soarer.alert.config.ClsUploadProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 ClsLogEventUploader 的行为。
 */
class ClsLogEventUploaderTest {

    @Test
    void missingWriterCredentialsKeepsUploaderUnavailable() {
        ClsUploadProperties properties = new ClsUploadProperties();
        properties.setEnabled(true);
        properties.setRegion("ap-guangzhou");
        properties.getTopics().setApp("app-topic");
        properties.getTopics().setDiagnosis("diagnosis-topic");

        ClsLogEventUploader uploader = new ClsLogEventUploader(properties);

        assertThat(uploader.isAvailable()).isFalse();
        assertThat(uploader.getDisabledReason())
                .isEqualTo("CLS writer credentials are missing");
    }

    @Test
    void missingRequiredTopicsKeepsUploaderUnavailable() {
        ClsUploadProperties properties = new ClsUploadProperties();
        properties.setEnabled(true);
        properties.setSecretId("writer-id");
        properties.setSecretKey("writer-key");

        ClsLogEventUploader uploader = new ClsLogEventUploader(properties);

        assertThat(uploader.isAvailable()).isFalse();
        assertThat(uploader.getDisabledReason())
                .isEqualTo("CLS app and diagnosis topic IDs are required");
    }
}

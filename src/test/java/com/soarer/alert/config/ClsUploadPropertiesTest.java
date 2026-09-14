package com.soarer.alert.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClsUploadPropertiesTest {

    @Test
    void resolvesDefaultEndpointAndInstanceIdentity() {
        ClsUploadProperties properties = new ClsUploadProperties();
        properties.setRegion("ap-guangzhou");
        properties.setHostName("ops-host-1");
        properties.setIp("10.0.0.8");
        properties.setPort("9900");

        assertThat(properties.resolvedEndpoint())
                .isEqualTo("https://ap-guangzhou.cls.tencentcs.com");
        assertThat(properties.resolvedHostName()).isEqualTo("ops-host-1");
        assertThat(properties.resolvedIp()).isEqualTo("10.0.0.8");
        assertThat(properties.instanceId()).isEqualTo("ops-host-1:soarer-alert-agent:9900");
    }

    @Test
    void endpointCanOverrideDefaultEndpoint() {
        ClsUploadProperties properties = new ClsUploadProperties();
        properties.setEndpoint("https://ap-guangzhou.cls.tencentcs.com");

        assertThat(properties.resolvedEndpoint())
                .isEqualTo("https://ap-guangzhou.cls.tencentcs.com");
    }

    @Test
    void detectsWriterCredentialsAndRequiredTopics() {
        ClsUploadProperties properties = new ClsUploadProperties();

        assertThat(properties.hasCredentials()).isFalse();
        assertThat(properties.hasTopics()).isFalse();

        properties.setSecretId("writer-id");
        properties.setSecretKey("writer-key");
        assertThat(properties.hasCredentials()).isTrue();
        assertThat(properties.hasTopics()).isFalse();

        properties.getTopics().setApp("app-topic");
        properties.getTopics().setDiagnosis("diagnosis-topic");
        assertThat(properties.hasTopics()).isTrue();
    }
}

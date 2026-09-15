package com.soarer.alert.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.net.InetAddress;

/**
 * ClsUploadProperties Spring 配置。
 */
@Configuration
@ConfigurationProperties(prefix = "cls.upload")
public class ClsUploadProperties {

    private boolean enabled;
    private String region = "ap-guangzhou";
    private String endpoint;
    private String secretId;
    private String secretKey;
    private String serviceName = "soarer-alert-agent";
    private String environment = "local";
    private String hostName;
    private String ip;
    private String port = "9900";
    private int sendThreadCount = 1;
    private int totalSizeInBytes = 8 * 1024 * 1024;
    private long maxBlockMs = 2000;
    private int batchCountThreshold = 100;
    private int lingerMs = 1000;
    private int retries = 3;
    private long baseRetryBackoffMs = 200;
    private long maxRetryBackoffMs = 5000;
    private final Topics topics = new Topics();

    public String resolvedEndpoint() {
        return isBlank(endpoint)
                ? "https://" + region + ".cls.tencentcs.com"
                : endpoint;
    }

    public String resolvedHostName() {
        if (!isBlank(hostName)) {
            return hostName;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            return "unknown-host";
        }
    }

    public String resolvedIp() {
        if (!isBlank(ip)) {
            return ip;
        }
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception ignored) {
            return "unknown-ip";
        }
    }

    public String instanceId() {
        return resolvedHostName() + ":" + serviceName + ":" + port;
    }

    public boolean hasCredentials() {
        return !isBlank(secretId) && !isBlank(secretKey);
    }

    public boolean hasTopics() {
        return !isBlank(topics.app) && !isBlank(topics.diagnosis);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getSecretId() {
        return secretId;
    }

    public void setSecretId(String secretId) {
        this.secretId = secretId;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public String getHostName() {
        return hostName;
    }

    public void setHostName(String hostName) {
        this.hostName = hostName;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public String getPort() {
        return port;
    }

    public void setPort(String port) {
        this.port = port;
    }

    public int getSendThreadCount() {
        return sendThreadCount;
    }

    public void setSendThreadCount(int sendThreadCount) {
        this.sendThreadCount = sendThreadCount;
    }

    public int getTotalSizeInBytes() {
        return totalSizeInBytes;
    }

    public void setTotalSizeInBytes(int totalSizeInBytes) {
        this.totalSizeInBytes = totalSizeInBytes;
    }

    public long getMaxBlockMs() {
        return maxBlockMs;
    }

    public void setMaxBlockMs(long maxBlockMs) {
        this.maxBlockMs = maxBlockMs;
    }

    public int getBatchCountThreshold() {
        return batchCountThreshold;
    }

    public void setBatchCountThreshold(int batchCountThreshold) {
        this.batchCountThreshold = batchCountThreshold;
    }

    public int getLingerMs() {
        return lingerMs;
    }

    public void setLingerMs(int lingerMs) {
        this.lingerMs = lingerMs;
    }

    public int getRetries() {
        return retries;
    }

    public void setRetries(int retries) {
        this.retries = retries;
    }

    public long getBaseRetryBackoffMs() {
        return baseRetryBackoffMs;
    }

    public void setBaseRetryBackoffMs(long baseRetryBackoffMs) {
        this.baseRetryBackoffMs = baseRetryBackoffMs;
    }

    public long getMaxRetryBackoffMs() {
        return maxRetryBackoffMs;
    }

    public void setMaxRetryBackoffMs(long maxRetryBackoffMs) {
        this.maxRetryBackoffMs = maxRetryBackoffMs;
    }

    public Topics getTopics() {
        return topics;
    }

    public static class Topics {
        private String app;
        private String host;
        private String middleware;
        private String diagnosis;

        public String getApp() {
            return app;
        }

        public void setApp(String app) {
            this.app = app;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public String getMiddleware() {
            return middleware;
        }

        public void setMiddleware(String middleware) {
            this.middleware = middleware;
        }

        public String getDiagnosis() {
            return diagnosis;
        }

        public void setDiagnosis(String diagnosis) {
            this.diagnosis = diagnosis;
        }
    }
}

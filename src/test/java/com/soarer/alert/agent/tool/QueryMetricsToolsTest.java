package com.soarer.alert.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 QueryMetricsTools 的行为。
 */
class QueryMetricsToolsTest {

    private QueryMetricsTools tools;

    @BeforeEach
    void setUp() {
        String now = Instant.now().minus(5, ChronoUnit.MINUTES).toString();
        String body = """
                {
                  "status": "success",
                  "data": {
                    "alerts": [
                      {
                        "labels": {
                          "alertname": "HighCPUUsage",
                          "instance": "host-a:9100",
                          "hostname": "server-a",
                          "job": "node-exporter",
                          "environment": "production"
                        },
                        "annotations": {
                          "description": "CPU usage is above 80%"
                        },
                        "state": "firing",
                          "activeAt": "__NOW__",
                        "value": "0.92"
                      },
                      {
                        "labels": {
                          "alertname": "HighCPUUsage",
                          "instance": "host-b:9100",
                          "hostname": "server-b",
                          "job": "node-exporter",
                          "environment": "production"
                        },
                        "annotations": {
                          "description": "CPU usage is above 80%"
                        },
                        "state": "firing",
                          "activeAt": "__NOW__",
                        "value": "0.91"
                      }
                    ]
                  }
                }
                """.replace("__NOW__", now);

        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    Request request = chain.request();
                    return new Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(ResponseBody.create(body, MediaType.parse("application/json")))
                            .build();
                })
                .build();

        tools = new QueryMetricsTools();
        ReflectionTestUtils.setField(tools, "prometheusBaseUrl", "http://mock-prometheus");
        ReflectionTestUtils.setField(tools, "timeout", 2);
        ReflectionTestUtils.setField(tools, "mockEnabled", false);
        tools.init();
        ReflectionTestUtils.setField(tools, "httpClient", client);
    }

    @AfterEach
    void tearDown() {
        // no external resources to release
    }

    @Test
    void shouldKeepMultiHostAlertsAndLabels() throws Exception {
        String result = tools.queryPrometheusAlerts();

        JsonNode root = new ObjectMapper().readTree(result);
        JsonNode alerts = root.path("alerts");
        assertThat(alerts).hasSize(2);
        assertThat(alerts.get(0).path("alert_name").asText()).isEqualTo("HighCPUUsage");
        assertThat(alerts.get(0).path("labels").path("instance").asText()).isEqualTo("host-a:9100");
        assertThat(alerts.get(0).path("labels").path("hostname").asText()).isEqualTo("server-a");
        assertThat(alerts.get(1).path("alert_name").asText()).isEqualTo("HighCPUUsage");
        assertThat(alerts.get(1).path("labels").path("instance").asText()).isEqualTo("host-b:9100");
        assertThat(alerts.get(1).path("labels").path("hostname").asText()).isEqualTo("server-b");
    }
}

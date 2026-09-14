package com.soarer.alert.service;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SupervisorAgent;
import com.soarer.alert.agent.tool.DateTimeTools;
import com.soarer.alert.agent.tool.InternalDocsTools;
import com.soarer.alert.agent.tool.QueryMetricsTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiOpsServiceTest {

    private AiOpsService aiOpsService;

    @BeforeEach
    void setUp() {
        aiOpsService = new AiOpsService();
        ReflectionTestUtils.setField(aiOpsService, "dateTimeTools", new DateTimeTools());
        ReflectionTestUtils.setField(
                aiOpsService,
                "internalDocsTools",
                new InternalDocsTools(mock(VectorSearchService.class))
        );
        ReflectionTestUtils.setField(aiOpsService, "queryMetricsTools", new QueryMetricsTools());
    }

    @Test
    void buildsSupervisorWithRoutingMainAgent() {
        SupervisorAgent supervisor = aiOpsService.buildSupervisorAgent(
                mock(ChatModel.class),
                new ToolCallback[0]
        );

        assertNotNull(supervisor.getMainAgent());
        assertEquals("ai_ops_router", supervisor.getMainAgent().name());
        assertTrue(supervisor.getSystemPrompt().contains("每次只输出一个 JSON 数组"));
        assertTrue(supervisor.getSystemPrompt().contains("[\"planner_agent\"]"));
        assertTrue(supervisor.getSystemPrompt().contains("[\"executor_agent\"]"));
        assertTrue(supervisor.getSystemPrompt().contains("[\"FINISH\"]"));
    }

    @Test
    void extractsPlannerOutputAsFinalReport() {
        OverAllState state = mock(OverAllState.class);
        when(state.value("planner_plan"))
                .thenReturn(Optional.of(new AssistantMessage("# 告警分析报告\n\n完整报告")));

        Optional<String> report = aiOpsService.extractFinalReport(state);

        assertTrue(report.isPresent());
        assertTrue(report.get().startsWith("# 告警分析报告"));
    }

    @Test
    void extractsReportFromMessagesWhenPlannerOutputIsMissing() {
        OverAllState state = mock(OverAllState.class);
        when(state.value("planner_plan")).thenReturn(Optional.empty());
        when(state.value("messages")).thenReturn(Optional.of(List.of(
                new AssistantMessage("[\"planner_agent\"]"),
                new AssistantMessage("# 告警分析报告\n\n从消息中恢复的报告")
        )));

        Optional<String> report = aiOpsService.extractFinalReport(state);

        assertTrue(report.isPresent());
        assertTrue(report.get().contains("从消息中恢复的报告"));
    }
}

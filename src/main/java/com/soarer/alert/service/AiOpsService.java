package com.soarer.alert.service;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SupervisorAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.soarer.alert.agent.tool.DateTimeTools;
import com.soarer.alert.agent.tool.InternalDocsTools;
import com.soarer.alert.agent.tool.QueryLogsTools;
import com.soarer.alert.agent.tool.QueryMetricsTools;
import com.soarer.alert.observability.DiagnosisAgentLifecycleHook;
import com.soarer.alert.service.persistence.DiagnosisPersistenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.Hook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * AI Ops 智能运维服务
 * 负责多 Agent 协作的告警分析流程
 */
@Service
public class AiOpsService {

    private static final Logger logger = LoggerFactory.getLogger(AiOpsService.class);

    @Autowired
    private DateTimeTools dateTimeTools;

    @Autowired
    private InternalDocsTools internalDocsTools;

    @Autowired
    private QueryMetricsTools queryMetricsTools;

    @Autowired(required = false)  // Mock 模式下才注册
    private QueryLogsTools queryLogsTools;

    @Autowired(required = false)
    private DiagnosisPersistenceService diagnosisPersistenceService;

    /**
     * 执行 AI Ops 告警分析流程
     *
     * @param chatModel      大模型实例
     * @param toolCallbacks  工具回调数组
     * @return 分析结果状态
     * @throws GraphRunnerException 如果 Agent 执行失败
     */
    public Optional<OverAllState> executeAiOpsAnalysis(ChatModel chatModel, ToolCallback[] toolCallbacks) throws GraphRunnerException {
        logger.info("开始执行 AI Ops 多 Agent 协作流程");

        SupervisorAgent supervisorAgent = buildSupervisorAgent(chatModel, toolCallbacks);
        String alertSnapshot = collectInitialAlertSnapshot();

        String taskPrompt = """
                你是企业级 SRE，接到自动化告警排查任务。
                系统已经在进入多 Agent 编排前强制执行了首轮 Prometheus 告警采集。请把下面的首轮采集结果作为本次诊断的事实起点，结合可用工具执行规划、执行、再规划的闭环，并最终按照固定模板输出《告警分析报告》。
                如果首轮结果显示存在告警，必须围绕这些告警继续收集必要的日志、知识库或其它证据；如果需要其它证据，必须实际调用对应工具，不能只根据报告模板直接编造结论。
                禁止编造虚假数据；如连续多次查询失败，需在最终报告中诚实说明无法完成的原因。

                ## 首轮 Prometheus 告警采集结果
                <prometheus-alert-snapshot>
                %s
                </prometheus-alert-snapshot>
                """;
        taskPrompt = taskPrompt.formatted(alertSnapshot);

        logger.info("调用 Supervisor Agent 开始编排...");
        RunnableConfig config = RunnableConfig.builder().build();
        String runId = org.slf4j.MDC.get("diagnosisRunId");
        if (runId != null && !runId.isBlank()) {
            try {
                DiagnosisAgentLifecycleHook.putExecutionContext(
                        config,
                        java.util.UUID.fromString(runId),
                        taskPrompt
                );
            } catch (IllegalArgumentException ignored) {
                logger.debug("Ignoring invalid diagnosis run ID in Agent context: {}", runId);
            }
        }
        return supervisorAgent.invoke(taskPrompt, config);
    }

    /**
     * AI Ops 必须先读取一次当前活动告警，避免模型在没有任何观测数据时直接生成报告。
     * 该调用发生在诊断 Worker 线程中，因此会沿用 diagnosisRunId 和当前 Supervisor 步骤的审计上下文。
     */
    String collectInitialAlertSnapshot() {
        if (queryMetricsTools == null) {
            return "{\"success\":false,\"message\":\"Prometheus 告警工具未配置\"}";
        }
        try {
            String snapshot = queryMetricsTools.queryPrometheusAlerts();
            return snapshot == null || snapshot.isBlank()
                    ? "{\"success\":false,\"message\":\"Prometheus 告警工具返回空结果\"}"
                    : snapshot;
        } catch (RuntimeException exception) {
            logger.warn("AI Ops 首轮 Prometheus 告警采集失败: {}", exception.getMessage());
            return "{\"success\":false,\"message\":\"Prometheus 告警采集失败: "
                    + escapeJson(exception.getMessage()) + "\"}";
        }
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "unknown";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    /**
     * 从执行结果中提取最终报告文本
     *
     * @param state 执行状态
     * @return 报告文本（如果存在）
     */
    public Optional<String> extractFinalReport(OverAllState state) {
        logger.info("开始提取最终报告...");

        Optional<String> report = state.value("planner_plan").flatMap(AiOpsService::toReportText)
                .or(() -> extractReportFromMessages(state));
        report.ifPresentOrElse(
                text -> logger.info("成功提取到最终报告，长度: {}", text.length()),
                () -> logger.warn("未能提取到最终报告")
        );
        return report;
    }

    /**
     * 构建 Supervisor 流程：路由 Agent 决定下一跳，Planner/Executor 负责具体工作。
     */
    SupervisorAgent buildSupervisorAgent(ChatModel chatModel, ToolCallback[] toolCallbacks) {
        return SupervisorAgent.builder()
                .name("ai_ops_supervisor")
                .description("负责调度 Planner 与 Executor 的多 Agent 控制器")
                .model(chatModel)
                .systemPrompt(buildSupervisorRoutingPrompt())
                .mainAgent(buildSupervisorRoutingAgent(chatModel))
                .subAgents(List.of(
                        buildPlannerAgent(chatModel, toolCallbacks),
                        buildExecutorAgent(chatModel, toolCallbacks)
                ))
                .build();
    }

    /**
     * Supervisor 框架要求 mainAgent 的最终 assistant 输出是可路由的 JSON 数组。
     */
    private ReactAgent buildSupervisorRoutingAgent(ChatModel chatModel) {
        var builder = ReactAgent.builder()
                .name("ai_ops_router")
                .description("根据 Planner 与 Executor 的最新输出决定下一个 Agent")
                .model(chatModel)
                .systemPrompt(buildSupervisorRoutingPrompt());
        addLifecycleHook(builder);
        return builder.build();
    }

    /**
     * 构建 Planner Agent
     */
    private ReactAgent buildPlannerAgent(ChatModel chatModel, ToolCallback[] toolCallbacks) {
        var builder = ReactAgent.builder()
                .name("planner_agent")
                .description("负责拆解告警、规划与再规划步骤")
                .model(chatModel)
                .systemPrompt(buildPlannerPrompt())
                .methodTools(buildMethodToolsArray())
                .tools(toolCallbacks)
                .outputKey("planner_plan");
        addLifecycleHook(builder);
        return builder.build();
    }

    /**
     * 构建 Executor Agent
     */
    private ReactAgent buildExecutorAgent(ChatModel chatModel, ToolCallback[] toolCallbacks) {
        var builder = ReactAgent.builder()
                .name("executor_agent")
                .description("负责执行 Planner 的首个步骤并及时反馈")
                .model(chatModel)
                .systemPrompt(buildExecutorPrompt())
                .methodTools(buildMethodToolsArray())
                .tools(toolCallbacks)
                .outputKey("executor_feedback");
        addLifecycleHook(builder);
        return builder.build();
    }

    private void addLifecycleHook(com.alibaba.cloud.ai.graph.agent.Builder builder) {
        if (diagnosisPersistenceService != null) {
            Hook hook = new DiagnosisAgentLifecycleHook(diagnosisPersistenceService);
            builder.hooks(hook);
        }
    }

    /**
     * 动态构建方法工具数组
     * 根据 cls.mock-enabled 决定是否包含 QueryLogsTools
     */
    private Object[] buildMethodToolsArray() {
        if (queryLogsTools != null) {
            // Mock 模式：包含 QueryLogsTools
            return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools, queryLogsTools};
        } else {
            // 真实模式：不包含 QueryLogsTools（由 MCP 提供日志查询功能）
            return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools};
        }
    }

    /**
     * 构建 Planner Agent 系统提示词
     */
    private String buildPlannerPrompt() {
        return """
                你是 Planner Agent，同时承担 Replanner 角色，负责：
                1. 读取当前输入任务 {input} 以及 Executor 的最近反馈 {executor_feedback}。
                2. 分析 Prometheus 告警、日志、内部文档等信息，制定可执行的下一步步骤。
                3. 在执行阶段，输出 JSON，包含 decision (PLAN|EXECUTE|FINISH)、step 描述、预期要调用的工具、以及必要的上下文。
                4. 调用任何腾讯云日志/主题相关工具时，region 参数必须使用连字符格式（如 ap-guangzhou），若不确定请省略以使用默认值。
                5. 严格禁止编造数据，只能引用工具返回的真实内容；如果连续 3 次调用同一工具仍失败或返回空结果，需停止该方向并在最终报告的结论部分说明"无法完成"的原因。
                
                ## 最终报告输出要求（CRITICAL）
                
                当 decision=FINISH 时，你必须：
                1. **不要输出 JSON 格式**
                2. **直接输出完整的 Markdown 格式报告文本**
                3. **报告必须严格遵循以下模板**：
                
                ```
                # 告警分析报告
                
                ---
                
                ## 📋 活跃告警清单
                
                | 告警名称 | 级别 | 目标服务 | 首次触发时间 | 最新触发时间 | 状态 |
                |---------|------|----------|-------------|-------------|------|
                | [告警1名称] | [级别] | [服务名] | [时间] | [时间] | 活跃 |
                | [告警2名称] | [级别] | [服务名] | [时间] | [时间] | 活跃 |
                
                ---
                
                ## 🔍 告警根因分析1 - [告警名称]
                
                ### 告警详情
                - **告警级别**: [级别]
                - **受影响服务**: [服务名]
                - **持续时间**: [X分钟]
                
                ### 症状描述
                [根据监控指标描述症状]
                
                ### 日志证据
                [引用查询到的关键日志]
                
                ### 根因结论
                [基于证据得出的根本原因]
                
                ---
                
                ## 🛠️ 处理方案执行1 - [告警名称]
                
                ### 已执行的排查步骤
                1. [步骤1]
                2. [步骤2]
                
                ### 处理建议
                [给出具体的处理建议]
                
                ### 预期效果
                [说明预期的效果]
                
                ---
                
                ## 🔍 告警根因分析2 - [告警名称]
                [如果有第2个告警，重复上述格式]
                
                ---
                
                ## 📊 结论
                
                ### 整体评估
                [总结所有告警的整体情况]
                
                ### 关键发现
                - [发现1]
                - [发现2]
                
                ### 后续建议
                1. [建议1]
                2. [建议2]
                
                ### 风险评估
                [评估当前风险等级和影响范围]
                ```
                
                **重要提醒**：
                - 最终输出必须是纯 Markdown 文本，不要包含 JSON 结构
                - 不要使用 "finalReport": "..." 这样的格式
                - 直接从 "# 告警分析报告" 开始输出
                - 所有内容必须基于工具查询的真实数据，严禁编造
                - 如果某个步骤失败，在结论中如实说明，不要跳过
                
                """;
    }

    /**
     * 构建 Executor Agent 系统提示词
     */
    private String buildExecutorPrompt() {
        return """
                你是 Executor Agent，负责读取 Planner 最新输出 {planner_plan}，只执行其中的第一步。
                - 确认步骤所需的工具与参数，尤其是 region 参数要使用连字符格式（ap-guangzhou）；若 Planner 未给出则使用默认区域。
                - 调用相应的工具并收集结果，如工具返回错误或空数据，需要将失败原因、请求参数一并记录，并停止进一步调用该工具（同一工具失败达到 3 次时应直接返回 FAILED）。
                - 将日志、指标、文档等证据整理成结构化摘要，标注对应的告警名称或资源，方便 Planner 填充"告警根因分析 / 处理方案执行"章节。
                - 以 JSON 形式返回执行状态、证据以及给 Planner 的建议，写入 executor_feedback，严禁编造未实际查询到的内容。


                输出示例：
                {
                  "status": "SUCCESS",
                  "summary": "近1小时未见 error 日志，仅有 info",
                  "evidence": "...",
                  "nextHint": "建议转向高占用进程"
                }
                """;
    }

    /**
     * 构建 Supervisor Agent 系统提示词
     */
    private String buildSupervisorSystemPrompt() {
        return buildSupervisorRoutingPrompt();
    }

    /**
     * 构建路由提示词。输出必须是纯 JSON 数组，这是框架解析 supervisor_next 的硬性要求。
     */
    private String buildSupervisorRoutingPrompt() {
        return """
                你是 AI Ops Supervisor Router，只负责选择下一个执行者，不负责写报告，也不调用工具。

                ## 输出格式（必须严格遵守）
                - 每次只输出一个 JSON 数组。
                - 不要输出解释、Markdown、代码块或其他文本。
                - 只能使用以下三种输出：
                  ["planner_agent"]
                  ["executor_agent"]
                  ["FINISH"]

                ## 路由规则
                1. 初始阶段，或 Executor 反馈需要补充证据、调整步骤、重新规划时，选择 planner_agent。
                2. Planner 最新输出的 decision=EXECUTE 且包含可执行的第一步时，选择 executor_agent。
                3. Planner 最新输出的 decision=FINISH，且已经生成完整《告警分析报告》时，选择 FINISH。
                4. 同一方向连续 3 次工具调用失败或没有数据时，先选择 planner_agent，让它生成如实说明失败原因的最终报告；看到该报告后再选择 FINISH。
                5. 无法判断时选择 planner_agent，不要提前 FINISH。

                """;
    }

    private static Optional<String> toReportText(Object output) {
        if (output instanceof AssistantMessage assistantMessage) {
            return toReportText(assistantMessage.getText());
        }
        if (output instanceof String text) {
            return toReportText(text);
        }
        return Optional.empty();
    }

    private static Optional<String> toReportText(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(text.trim());
    }

    private static Optional<String> extractReportFromMessages(OverAllState state) {
        Object messagesValue = state.value("messages").orElse(null);
        if (!(messagesValue instanceof List<?> messages) || messages.isEmpty()) {
            return Optional.empty();
        }

        for (int index = messages.size() - 1; index >= 0; index--) {
            if (messages.get(index) instanceof AssistantMessage assistantMessage) {
                String text = assistantMessage.getText();
                if (text != null && text.contains("# 告警分析报告")) {
                    return Optional.of(text.trim());
                }
            }
        }
        return Optional.empty();
    }
}

# SoarerAlertAgent 架构说明

## 总体结构

```text
static HTML/CSS/JS
  index.html / ops.html
        |
  REST + SSE + WebSocket
        |
  Spring Boot 4.1 application
        |
  ChatService / AiOpsService / Document pipeline
        |
  Redis Stream workers
        |
  PostgreSQL + JPA + Flyway + pgvector
        |
  RustFS (S3 compatible)
        |
  DashScope qwen3.7-flash + text-embedding-v3
```

## 业务链路

### RAG 问答

1. 用户发起 `/api/chat` 或 `/api/chat_stream`。
2. 会话上下文和用户问题进入 ChatService。
3. 通过 pgvector 检索内部运维文档。
4. 调用 DashScope 生成回答，支持工具调用和流式输出。
5. 会话与消息持久化到 PostgreSQL。

### 文档索引

1. `/api/upload` 校验文件大小、扩展名和 Tika 真实类型。
2. 计算 SHA-256 并写入 `ops_document`。
3. 原始文件保存到 RustFS。
4. 发布 `soarer:document-index:stream`。
5. DocumentIndexWorker 异步解析、清洗、切分、Embedding。
6. 向量写入 pgvector，分片元数据写入 `ops_document_chunk`。
7. 失败任务按尝试次数重试，最终失败进入死信流并记录失败原因。

### AIOps 诊断

1. `/api/ai_ops` 创建 `QUEUED` 诊断任务。
2. 任务发布到 `soarer:diagnosis:stream`。
3. DiagnosisWorker 将状态置为 `RUNNING`，并写入 MDC。
4. `ai_ops_router` 依次调度 Planner 与 Executor。
5. Agent 工具调用由切面记录耗时、结果和 `ops_tool_invocation`。
6. 最终报告写入 `ops_diagnosis_report`，run 状态更新为 `SUCCESS` 或 `FAILED`。
7. SSE 轮询数据库状态，前端时间线展示步骤、工具和报告。
8. WebSocket 支持人工查询状态和取消任务。

## 持久化模型

| 表 | 职责 |
|---|---|
| `vector_store` | pgvector 运维知识库 |
| `ops_document` | 文档状态、Hash、对象 key、失败原因 |
| `ops_document_chunk` | 文档切分与向量关联 |
| `ops_diagnosis_run` | 诊断任务状态、尝试次数、时间 |
| `ops_agent_step` | Planner / Executor / Supervisor 步骤 |
| `ops_tool_invocation` | 工具名称、状态、耗时、错误 |
| `ops_diagnosis_report` | 最终诊断报告 |

## 可观测性

- Actuator 暴露 health、info、prometheus。
- `InstrumentedChatModel` 包装模型调用，记录延迟与结果。
- Agent 与 Worker 写入 `traceId`、`sessionId`、`diagnosisRunId`。
- `ToolInvocationObservabilityAspect` 统计工具指标并持久化诊断工具明细。
- `RedisStreamMetricsBinder` 输出 pending、lag、死信长度。
- 生产 profile 输出 Logstash JSON，Prometheus 按 15 秒采集。

## 部署边界

应用容器与基础设施在同一个 Docker network 中通信。外部访问只暴露应用、Prometheus 和可选 Loki 端口。DashScope、数据库、Redis、RustFS 和 MCP 配置全部来自环境变量。RustFS 保存原始文件，PostgreSQL 保存业务与向量数据，Redis 只承担异步任务和轻量状态。

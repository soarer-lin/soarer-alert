# 测试与验收

## 单元测试

使用 JDK 25 运行单元测试：

```powershell
$env:JAVA_HOME="<path-to-jdk-25>"
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -s .mvn\maven-settings.xml test
```

`scripts/dev-app.ps1` 会自动设置同一个 JDK。

打包：

```powershell
mvn -s .mvn\maven-settings.xml -DskipTests package
```

## 测试范围

- 文档解析、清洗、切分、Hash 去重。
- RustFS 对象存储和健康检查。
- PostgreSQL 持久化、Flyway 迁移、pgvector 服务。
- Redis Stream 发布、消费、ACK、重试和死信。
- 诊断任务状态机与报告持久化。
- Agent 编排、报告提取和工具调用审计。
- REST、SSE、WebSocket 接口。
- 可观测性指标、MDC、模型包装器。

## 冒烟清单

使用本地开发模式启动基础设施和应用：

```powershell
.\scripts\dev-infra.ps1
$env:DASHSCOPE_API_KEY="your-real-key"
$env:AUTH_ADMIN_USERNAME="admin"
$env:AUTH_ADMIN_INITIAL_PASSWORD="Local-Boot-123456"
.\scripts\dev-app.ps1
```

依次访问：

```powershell
Invoke-RestMethod http://localhost:9900/actuator/health

$loginBody = @{
    username = "admin"
    password = "Local-Boot-123456"
} | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri http://localhost:9900/api/auth/login `
  -ContentType "application/json" -Body $loginBody -SessionVariable session

Invoke-RestMethod http://localhost:9900/api/auth/me -WebSession $session
Invoke-RestMethod http://localhost:9900/api/ai_ops/overview -WebSession $session
Invoke-RestMethod http://localhost:9900/api/ai_ops/runs -WebSession $session
Invoke-RestMethod http://localhost:9900/api/documents -WebSession $session
(Invoke-WebRequest http://localhost:9900/actuator/prometheus).Content | Select-String "superbiz_"
```

首次登录会返回 `mustChangePassword=true`，需要先在账号设置页修改初始密码。修改后再使用新密码登录执行后续验收。

浏览器检查：

- `http://localhost:9900/`：聊天页面。
- `http://localhost:9900/ops.html`：AIOps 控制台。
- `http://localhost:19090/targets`：Prometheus 采集目标。
- `http://localhost:19090/alerts`：Prometheus 告警列表。

多主机告警验收：

- 在 `docker/prometheus/targets/node-hosts.yml` 或 `windows-hosts.yml` 中至少配置两台主机。
- 触发同名 CPU、内存或磁盘告警。
- 调用 `queryPrometheusAlerts`，确认返回多条记录，且每条记录的 `labels.instance` / `labels.hostname` 能区分不同主机。

WebSocket 地址：

```text
ws://localhost:9900/ws/diagnosis
```

连接后会收到 `{"type":"ready"}`。发送 `status` 或 `cancel` 命令可以验证控制链路。

## 真实模型验收

真实验收使用以下环境变量，密钥只存在于当前进程：

```powershell
$env:DASHSCOPE_API_KEY="your-real-key"
$env:DASHSCOPE_CHAT_MODEL="qwen3.7-flash"
$env:DASHSCOPE_EMBEDDING_MODEL="text-embedding-v3"
```

验收路径：

1. 上传 Markdown 或 TXT 文档。
2. 确认 `ops_document.status=SUCCESS`。
3. 发起 `/api/ai_ops`。
4. 观察状态 `QUEUED -> RUNNING -> SUCCESS`。
5. 确认 `ops_agent_step`、`ops_tool_invocation`、`ops_diagnosis_report` 均有记录。
6. 确认 Redis 消费组 `pending=0`，Prometheus 指标正常增长。

占位密钥 `local-smoke-test-key` 只适合应用启动和接口冒烟，不能用于真实模型调用。

## 真实 CLS-MCP 验收

在真实模型验收的基础上追加以下环境变量，密钥仍只保存在当前进程：

```powershell
$env:TENCENTCLOUD_SECRET_ID="your-secret-id"
$env:TENCENTCLOUD_SECRET_KEY="your-secret-key"
$env:TENCENTCLOUD_REGION="ap-guangzhou"
$env:MCP_CLIENT_ENABLED="true"
$env:CLS_MOCK_ENABLED="false"
```

启动并验收：

```powershell
.\scripts\dev-infra.ps1 -WithMcp
.\scripts\dev-app.ps1
```

1. `cls-mcp` 容器运行，宿主机应用健康检查返回 `UP`。
2. 应用日志没有 MCP 连接或鉴权错误。
3. 发起 `/api/ai_ops` 诊断，任务最终为 `SUCCESS`。
4. `ops_tool_invocation` 中出现 `SearchLog`、`DescribeTopics` 或 `TextToSearchLogQuery` 记录。
5. 诊断报告引用真实 CLS 日志，而不是 Mock 日志。

## 真实 CLS 上传验收

在真实 CLS-MCP 验收基础上，使用 writer 子账号启用上传：

```powershell
$env:CLS_UPLOAD_ENABLED="true"
$env:CLS_WRITER_SECRET_ID="your-writer-secret-id"
$env:CLS_WRITER_SECRET_KEY="your-writer-secret-key"
$env:CLS_SERVICE_NAME="soarer-alert-agent"
$env:CLS_ENVIRONMENT="local"
```

启动完整链路：

```powershell
.\scripts\dev-infra.ps1 -WithMcp
.\scripts\dev-app.ps1
```

创建一个诊断任务并记录 run ID：

```powershell
$body = '{"requestText":"CPU usage is above 90 percent, diagnose the host"}'
$run = Invoke-RestMethod -Method Post -Uri http://localhost:9900/api/ai_ops/runs `
  -ContentType "application/json" -Body $body -WebSession $session
$runId = $run.data.id
$runId
```

轮询状态和报告：

```powershell
Invoke-RestMethod http://localhost:9900/api/ai_ops/runs/$runId -WebSession $session
Invoke-RestMethod http://localhost:9900/api/ai_ops/runs/$runId/report -WebSession $session
```

验收标准：

1. `app-logs` 中出现 `service:"soarer-alert-agent"`、`environment:"local"`、`log_type:"springboot"`。
2. `diagnosis-logs` 中出现 `task_id:"<runId>"`，并覆盖任务状态事件。
3. `ops_tool_invocation` 中出现 CLS 查询工具调用。
4. 最终诊断报告包含真实 CLS 日志证据，而不是 Mock 日志。
5. 应用日志中没有 CLS 鉴权失败、主题 ID 错误或上传异常。

# SoarerAlertAgent 运维手册

本文覆盖本地运行、容器化部署、监控、日志、备份和常见故障处理。所有密钥都必须通过环境变量注入，禁止写入仓库文件。

## 端口

| 服务 | 默认地址 | 说明 |
|---|---|---|
| SoarerAlertAgent | `http://localhost:9900` | Web、REST、SSE、WebSocket |
| PostgreSQL | `localhost:15432` | 本机进程访问 |
| Redis | `localhost:16379` | 本机进程访问 |
| RustFS S3 | `http://localhost:19000` | 本机进程访问 |
| Prometheus | `http://localhost:19090` | 容器内使用 9090 |
| Loki | `http://localhost:13100` | `observability` profile |
| Tencent CLS-MCP | `http://localhost:13000/sse` | `mcp` profile，容器内使用 3000 |

## 环境准备

1. 安装 Docker Desktop 和 JDK 25。当前本机只有 JDK 17 时，项目会自动启用 `jdk17-compat` profile 完成本地验证。
2. 复制 `.env.example` 为 `.env`。
3. 在当前 shell 中设置真实百炼密钥，不要把密钥写入 `.env` 或文档：

```powershell
$env:DASHSCOPE_API_KEY="your-real-key"
$env:AUTH_ADMIN_USERNAME="admin"
$env:AUTH_ADMIN_INITIAL_PASSWORD="Local-Boot-123456"
```

## 本地进程运行

```powershell
# 终端 1：启动 PostgreSQL、Redis、RustFS 和 Prometheus
.\scripts\dev-infra.ps1

# 终端 2：应用直接运行在宿主机，改代码后无需重建镜像
$env:DASHSCOPE_API_KEY="your-real-key"
$env:AUTH_ADMIN_USERNAME="admin"
$env:AUTH_ADMIN_INITIAL_PASSWORD="Local-Boot-123456"
.\scripts\dev-app.ps1
```

默认 Compose 不会启动 `soarer-alert-app` 容器。本地 Prometheus 通过
`host.docker.internal:9900` 抓取宿主机应用的 `/actuator/prometheus`。

## 腾讯云 CLS-MCP 接入

### 1. 准备腾讯云凭证

1. 登录腾讯云，创建 `reader` 和 `writer` 两个专用子账号，并分别生成 `SecretId` / `SecretKey`。
2. 给 `reader` 子账号授权以下只读操作，它只用于 `cls-mcp-server` 查询：

```json
{
  "version": "2.0",
  "statement": [
    {
      "effect": "allow",
      "action": [
        "cls:SearchLog",
        "cls:DescribeTopics",
        "cls:DescribeLogsets",
        "cls:DescribeIndex",
        "cls:DescribeLogHistogram"
      ],
      "resource": ["*"]
    }
  ]
}
```

3. 给 `writer` 子账号只授予向日志集 `superbiz-agent` 上传日志的权限。它只用于应用日志和诊断事件上传，不要与 reader 凭证混用。
4. 确认日志集、日志主题和 `TENCENTCLOUD_REGION` 在同一个地域，并且日志主题已开启索引。
5. `cls-mcp-server` 本身开源免费；费用来自 CLS 的日志存储、索引和查询。测试建议日志保存 7 天，只开启必要字段索引，测试完成后清空或删除日志主题。

不要把腾讯云密钥写入 `.env`、Compose、代码或文档。以下命令只在当前 PowerShell 进程中注入密钥。

### 2. 启动真实 CLS 日志链路

```powershell
$env:DASHSCOPE_API_KEY="your-real-key"
$env:TENCENTCLOUD_SECRET_ID="your-secret-id"
$env:TENCENTCLOUD_SECRET_KEY="your-secret-key"
$env:TENCENTCLOUD_REGION="ap-guangzhou"
$env:MCP_CLIENT_ENABLED="true"
$env:CLS_MOCK_ENABLED="false"

.\scripts\dev-infra.ps1 -WithMcp

$env:DASHSCOPE_API_KEY="your-real-key"
.\scripts\dev-app.ps1
```

默认配置：

| 配置 | 默认值 | 说明 |
|---|---|---|
| `CLS_MCP_URL` | `http://localhost:13000` | 宿主机 Java 进程访问 MCP 服务 |
| `CLS_MCP_SSE_ENDPOINT` | `/sse` | CLS-MCP SSE 端点 |
| `CLS_MCP_PORT` | `13000` | 宿主机调试端口 |
| `CLS_MCP_MAX_LENGTH` | `15000` | MCP 返回内容长度限制 |
| `TENCENTCLOUD_API_BASE_HOST` | `tencentcloudapi.com` | 公网 API；服务器在腾讯云 VPC 时可改为 `internal.tencentcloudapi.com` |

容器化应用访问 MCP 时使用 `http://cls-mcp:3000`；本地宿主机进程使用
`http://localhost:13000`。

### 3. 启用日志上传

应用日志和诊断事件由 Spring Boot 内的官方 `AsyncProducerClient` 直接上传到腾讯云 CLS。查询链路则保持为 `app -> cls-mcp-server -> SearchLog`，两条链路使用不同凭证：

| 凭证 | 环境变量 | 用途 |
|---|---|---|
| reader | `TENCENTCLOUD_SECRET_ID` / `TENCENTCLOUD_SECRET_KEY` | MCP 查询日志 |
| writer | `CLS_WRITER_SECRET_ID` / `CLS_WRITER_SECRET_KEY` | 上传应用日志和诊断事件 |

日志主题规划：

| 主题 | Topic ID | 当前用途 |
|---|---|---|
| `app-logs` | `d4fd734b-0451-4567-92a7-e3bd68d9c5d8` | SoarerAlertAgent Spring Boot 应用日志 |
| `diagnosis-logs` | `e574cb90-10ba-445e-8467-cc99f563ca11` | 诊断任务状态、重试、成功、失败和 Agent step 事件 |
| `host-logs` | `d2a46e90-7a30-447e-bf5c-a9113e18ab88` | 预留：主机 / exporter 相关日志采集 |
| `middleware-logs` | `2708c9fa-9d93-4f94-9508-659108eb70c7` | 预留：数据库、Redis、消息队列等中间件日志采集 |

在当前 PowerShell 进程中注入 writer 凭证并启用上传：

```powershell
$env:CLS_UPLOAD_ENABLED="true"
$env:CLS_WRITER_SECRET_ID="your-writer-secret-id"
$env:CLS_WRITER_SECRET_KEY="your-writer-secret-key"
$env:TENCENTCLOUD_REGION="ap-guangzhou"
```

常用区分字段已经随日志写入：`service`、`environment`、`host_name`、`ip`、`port`、`instance_id`、`log_type`。多实例部署时建议显式设置 `CLS_HOST_NAME` 和 `CLS_SERVICE_IP`，避免容器内默认地址不便于定位。

腾讯云控制台常用检索语句：

```text
service:"soarer-alert-agent" AND environment:"local"
log_type:"springboot"
log_type:"diagnosis"
task_id:"<diagnosisRunId>"
diagnosis_run_id:"<diagnosisRunId>"
```

### 4. 接入验收

1. 确认 MCP 容器和宿主机应用状态：

```powershell
docker compose ps cls-mcp
Invoke-RestMethod http://localhost:9900/actuator/health
docker compose logs -f cls-mcp
```

2. 确认 `dev-app.ps1` 终端中的应用日志没有 MCP 连接失败，并能看到 MCP 工具初始化信息。
3. 打开 `http://localhost:9900/ops.html`，发起一次 AIOps 诊断。
4. 确认任务从 `RUNNING` 进入 `SUCCESS`，报告包含真实 CLS 日志证据。
5. 查询工具调用审计：

```sql
SELECT tool_name, status, duration_ms, error_message, started_at
FROM ops_tool_invocation
ORDER BY started_at DESC
LIMIT 20;
```

真实模式下应能看到 `SearchLog`、`DescribeTopics` 或 `TextToSearchLogQuery` 等工具记录，同时 `soarer_aiops_tool_calls_total` 指标增长。

启用日志上传时，还需在 `app-logs` 中看到 `log_type:"springboot"` 日志，在 `diagnosis-logs` 中看到任务 `QUEUED`、`RUNNING`、`SUCCESS` 或 `FAILED` 事件。

### 5. 回退 Mock 模式

演示或排查 MCP 问题时，可以恢复本地 Mock 日志：

```powershell
$env:MCP_CLIENT_ENABLED="false"
$env:CLS_MOCK_ENABLED="true"

.\scripts\dev-app.ps1
```

Mock 模式使用项目内 `queryLogs` 和 `getAvailableLogTopics`，不会调用腾讯云，也不会产生 CLS 查询费用。

## 容器化运行

默认只启动 PostgreSQL、Redis、RustFS 和 Prometheus。需要容器化调试应用时显式启用
`app` profile：

```powershell
$env:DASHSCOPE_API_KEY="your-real-key"
docker compose --profile app up -d --build --wait
```

应用和 CLS-MCP 一起容器化运行：

```powershell
docker compose --profile app --profile mcp up -d --build --wait
```

增加 Loki 和 Promtail 日志链路：

```powershell
docker compose --profile app --profile observability up -d
```

查看状态和日志：

```powershell
docker compose ps
docker compose logs -f app
docker compose logs -f prometheus
```

停止默认基础设施，保留数据卷：

```powershell
docker compose down
```

停止容器化应用时使用 `docker compose --profile app down`；同时包含 MCP 时加上
`--profile mcp`。

## 健康与监控

| 检查项 | URL |
|---|---|
| 应用健康 | `http://localhost:9900/actuator/health` |
| Prometheus 指标 | `http://localhost:9900/actuator/prometheus` |
| Prometheus UI | `http://localhost:19090/targets` |
| 运维控制台 | `http://localhost:9900/ops.html` |
| AIOps 总览 | `http://localhost:9900/api/ai_ops/overview` |

核心自定义指标：

| 指标 | 含义 |
|---|---|
| `soarer.aiops.model.calls` | 模型调用成功 / 失败次数 |
| `soarer.aiops.model.call.duration` | 模型调用耗时 |
| `soarer.aiops.agent.invocations` | Agent 调用结果 |
| `soarer.aiops.agent.invocation.duration` | Agent 调用耗时 |
| `soarer.aiops.tool.calls` | 工具调用结果 |
| `soarer.aiops.tool.call.duration` | 工具调用耗时 |
| `soarer.aiops.diagnosis.runs` | 诊断任务结果与尝试次数 |
| `soarer.aiops.document.index.runs` | 文档索引结果与尝试次数 |
| `soarer.aiops.stream.retries` | Redis Stream 重试次数 |
| `soarer.redis.stream.pending` | 消费组待 ACK 数 |
| `soarer.redis.stream.lag` | 消费组 lag |
| `soarer.redis.stream.dead-letter.size` | 死信流长度 |

常用 PromQL：

```promql
sum by (outcome) (rate(soarer_aiops_model_calls_total[5m]))
sum by (tool, outcome) (rate(soarer_aiops_tool_calls_total[5m]))
soarer_redis_stream_pending{stream="diagnosis"}
```

### 多主机监控

Prometheus 的主机目标通过 `file_sd_configs` 管理，配置文件位于 `docker/prometheus/targets/`：

- `node-hosts.yml`：Linux / macOS 主机，默认使用 `host.docker.internal:9100`。
- `windows-hosts.yml`：Windows 主机，默认使用 `host.docker.internal:9182`。

每台服务器只需要在对应文件里追加一个 target，并保持 `hostname`、`environment`、`os` 标签稳定。`queryPrometheusAlerts` 会保留 Prometheus 返回的完整 `labels`，并按告警实例逐条返回，不再按 `alertname` 全局去重。因此同一类告警在多台主机上同时触发时，Agent 仍能准确区分 `instance` / `hostname`。

CPU、内存、磁盘的 Linux 与 Windows 告警规则位于 `docker/prometheus/rules/host-alerts.yml`，阈值分别为 80%、85%、85%，持续 5 分钟后触发。新增或修改 target 后，Prometheus 会按 `file_sd_configs.refresh_interval` 自动加载，无需重建容器。

## 日志

应用日志包含 `traceId`、`sessionId`、`diagnosisRunId`。诊断请求可以从 REST 或 WebSocket 响应、数据库 `ops_diagnosis_run` 表或日志中的 run ID 串起完整链路。

生产 profile 输出 Logstash JSON。默认情况下容器日志由 Docker 收集；启用 `observability` profile 后，Promtail 会把容器日志发送到 Loki。

## 备份与恢复

备份 PostgreSQL：

```powershell
docker compose exec -T postgres pg_dump -U superbiz -d soarer_alert -F c > soarer-alert.dump
```

恢复 PostgreSQL：

```powershell
Get-Content soarer-alert.dump -AsByteStream -Raw | docker compose exec -T postgres pg_restore -U superbiz -d soarer_alert --clean --if-exists
```

RustFS 原始文件保存在 `soarer-alert-rustfs-data` 卷。生产环境建议把该卷挂载到企业对象存储或纳入主机级备份；PostgreSQL 备份不会包含原始文件，只包含对象 key 和向量元数据。

## 常见故障

| 现象 | 处理 |
|---|---|
| 应用健康失败 | `docker compose logs app`，先确认数据库、Redis、RustFS 容器健康 |
| Prometheus target down | 确认 `http://localhost:9900/actuator/prometheus` 可访问 |
| Redis pending 增长 | 观察 `soarer.redis.stream.pending`，查看 Worker 日志和死信流 |
| 文档索引失败 | 查询 `ops_document.failure_reason` 和 `/api/documents` |
| 诊断长时间 RUNNING | 查看 `ops_agent_step`、`ops_tool_invocation` 和 run ID 关联日志 |
| 容器无法读取 API Key | 只在 shell 或部署平台环境变量中设置 `DASHSCOPE_API_KEY` |
| MCP 连接失败 | 确认 `cls-mcp` 容器已启动，`CLS_MCP_URL` 和 `CLS_MCP_SSE_ENDPOINT` 正确 |
| CLS 鉴权失败 | 检查 `TENCENTCLOUD_SECRET_ID` / `TENCENTCLOUD_SECRET_KEY` 和子账号授权策略 |
| CLS 查不到日志 | 检查地域、日志主题、时间范围和索引配置；先在腾讯云控制台验证原始日志存在 |

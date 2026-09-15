# SoarerAlert

[![CI](https://github.com/soarer-lin/soarer-alert/actions/workflows/ci.yml/badge.svg)](https://github.com/soarer-lin/soarer-alert/actions/workflows/ci.yml)

智能告警排查平台

SoarerAlert 是一套运维 OnCall 智能告警排查平台，内置自研 SoarerAlertAgent 大模型智能体。支持多源告警聚合、告警降噪、故障根因智能排查、值班轮换管理、工单辅助生成，大幅提升运维故障排查与响应效率。

## 📖 项目简介

企业级智能业务代理系统，包含两大核心模块：

### 1. RAG 智能问答
集成 PostgreSQL + pgvector 向量数据库和阿里云 DashScope，提供基于检索增强生成的智能问答能力，支持多轮对话和流式输出。

### 2. AIOps 智能运维
基于 AI Agent 的自动化运维系统，采用 Planner-Executor-Replanner 架构，实现告警分析、日志查询、智能诊断和报告生成。

## 🚀 核心特性

- ✅ **RAG 问答**: 向量检索 + 多轮对话 + 流式输出
- ✅ **AIOps 运维**: 智能诊断 + 多 Agent 协作 + 自动报告
- ✅ **多格式知识库**: TXT/Markdown/PDF/DOC/DOCX 校验、解析、清洗、去重与切分
- ✅ **对象存储**: RustFS 保存原始文档，S3 API 保持存储层可替换
- ✅ **业务持久化**: PostgreSQL + JPA + Flyway 保存文档、会话、诊断过程和报告
- ✅ **工具集成**: 文档检索、告警查询、日志分析、时间工具
- ✅ **腾讯云 CLS MCP**: 可选 SSE 模式接入真实腾讯云日志，默认保留 Mock 演示模式
- ✅ **会话管理**: 上下文维护、历史管理、自动清理
- ✅ **Web 界面**: 提供测试界面和 RESTful API
- ✅ **异步任务**: Redis Stream 驱动文档索引与 AIOps 诊断，支持重试和死信
- ✅ **可观测性**: Actuator、Micrometer、Prometheus、结构化日志与调用链上下文
- ✅ **容器化部署**: Docker Compose 一键启动应用、数据库、缓存、对象存储与监控


## 🛠️ 技术栈

| 技术 | 版本 | 说明 |
|------|------|------|
| Java | 25 | 开发语言（当前本地可用 JDK 17 时自动使用兼容 profile 验证） |
| Spring Boot | 4.1.0 | 应用框架 |
| Spring AI | 2.0.0-M1 | AI 与 Agent 能力 |
| Spring AI Alibaba | 2.0.0-M1.1 | DashScope 与 Agent Framework |
| DashScope | 2.17.0 | 阿里云百炼 AI 服务 |
| Chat 模型 | qwen3.7-flash | 对话与 Agent 编排 |
| Embedding 模型 | text-embedding-v3 | 1024 维文本向量 |
| PostgreSQL + pgvector | 16 / 0.8 | 业务与向量数据库 |
| RustFS | S3 API | 原始文档与后续证据、报告存储 |
| Flyway | 12.4 | 数据库结构迁移 |
| Apache Tika | 2.9.2 | 文档真实类型检测与文本提取 |
| AWS SDK S3 | 2.29.51 | 访问 RustFS S3 兼容接口 |

## 📦 核心模块

```
soarer-alert/
├── src/main/java/org/example/
│   ├── controller/
│   │   └── ChatController.java        # 统一接口控制器 ⭐
│   ├── service/
│   │   ├── ChatService.java           # 对话服务 ⭐
│   │   ├── AiOpsService.java          # AIOps 服务 ⭐
│   │   ├── RagService.java            # RAG 服务
│   │   ├── Vector*.java               # 向量服务
│   │   └── persistence/               # JPA 持久化服务
│   ├── agent/tool/                    # Agent 工具集
│   │   ├── DateTimeTools.java         # 时间工具
│   │   ├── InternalDocsTools.java     # 文档检索
│   │   ├── QueryMetricsTools.java     # 告警查询
│   │   └── QueryLogsTools.java        # 日志查询
│   └── config/                        # 配置类
├── src/main/resources/
│   ├── static/                        # Web 界面
│   └── application.yml                # 应用配置
└── aiops-docs/                        # 运维文档库
```


## 📡 核心接口

### 1. 智能问答接口

**流式对话（推荐）**
```bash
POST /api/chat_stream
Content-Type: application/json

{
  "Id": "session-123",
  "Question": "什么是向量数据库？"
}
```
支持 SSE 流式输出、自动工具调用、多轮对话。

**普通对话**
```bash
POST /api/chat
Content-Type: application/json

{
  "Id": "session-123",
  "Question": "什么是向量数据库？"
}
```
一次性返回完整结果，支持工具调用和多轮对话。

### 2. AIOps 智能运维接口

```bash
POST /api/ai_ops
```
自动执行告警分析流程，生成运维报告（SSE 流式输出）。接口会先创建 `QUEUED` 诊断任务并投递 Redis Stream，随后 SSE 轮询任务状态；Worker 执行多 Agent 流程并把诊断 run、Agent step 和最终报告写入 PostgreSQL。失败任务默认最多尝试 3 次，重试期间状态为 `RETRYING`，超过次数后写入死信队列并将任务标记为 `FAILED`。

AIOps 编排使用 `ai_ops_router` 作为 Supervisor 的专用路由 Agent。它只输出 `["planner_agent"]`、`["executor_agent"]` 或 `["FINISH"]`，Planner 与 Executor 继续作为子 Agent 执行计划、取证和反馈。最终报告会从 `planner_plan`、`executor_feedback` 或消息状态中提取，并以 `# 告警分析报告` 作为恢复标记。

#### AIOps 控制台 API

- `GET /api/ai_ops/overview`：告警、任务、报告和知识库总览。
- `GET /api/ai_ops/runs`：诊断任务分页列表。
- `POST /api/ai_ops/runs`：创建诊断任务。
- `GET /api/ai_ops/runs/{runId}`：任务详情。
- `GET /api/ai_ops/runs/{runId}/report`：诊断报告。
- `GET /api/ai_ops/runs/{runId}/timeline`：Agent 步骤与工具调用时间线。
- `POST /api/ai_ops/runs/{runId}/cancel`：取消任务。
- `GET /api/ai_ops/alerts`：活跃告警。
- `GET /api/documents`：文档索引状态。

WebSocket 控制地址为 `ws://localhost:9900/ws/diagnosis`，连接成功后服务端发送 `{"type":"ready"}`，支持 `status` 与 `cancel` 命令。控制台页面为 `http://localhost:9900/ops.html`。

### 3. 会话管理

- `POST /api/chat/clear` - 清空会话历史
- `GET /api/chat/session/{sessionId}` - 获取会话信息

### 4. 文件管理

- `POST /api/upload` - 上传 TXT/Markdown/PDF/DOC/DOCX 并自动解析、切分、索引
- `GET /vector-store/health` - PostgreSQL + pgvector 健康检查
- `GET /object-store/health` - RustFS Bucket 健康检查

上传接口会做 50MB 大小限制、扩展名与 Tika 检测类型一致性校验、UTF-8/BOM 校验、
SHA-256 内容去重，以及 Markdown 标题/段落/代码块语义切分。原始文件会先保存到
RustFS，响应返回 `storageKey` 和 `storageUrl`，新上传的 `filePath` 为空。重复内容
会返回 `duplicate=true` 并跳过重复索引；索引失败时对象仍保留在 RustFS，响应中
记录 `failureReason`。文档状态、Hash 和分片记录由 Flyway 管理并持久化到
`ops_document` / `ops_document_chunk`。

> 第三阶段已完成 TXT/Markdown 核心逻辑的单元测试；本仓库当前未包含真实 PDF/DOCX
> 样例，端到端解析尚未验证。


## ⚙️ 核心配置

### 配置文件

公共配置位于 `src/main/resources/application.yml`，环境差异位于：

- `application-local.yml`：本地开发，默认关闭占位 MCP，启用 Prometheus/CLS Mock。
- `application-test.yml`：测试环境，关闭 MCP，启用懒加载和 Mock。
- `application-prod.yml`：生产环境，从环境变量读取 DashScope、PostgreSQL、Prometheus 和 CLS/MCP 配置。

使用 `SPRING_PROFILES_ACTIVE` 选择配置，默认使用 `local`。

### application-local.yml

```yaml
spring:
  ai:
    dashscope:
      api-key: ${DASHSCOPE_API_KEY:your-api-key-here}
```

### 环境变量

```bash
export DASHSCOPE_API_KEY=your-api-key
export DASHSCOPE_CHAT_MODEL=qwen3.7-flash
export DASHSCOPE_CHAT_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
export DASHSCOPE_CHAT_COMPLETIONS_PATH=/chat/completions
export DASHSCOPE_CHAT_ENABLE_THINKING=false
export DASHSCOPE_EMBEDDING_MODEL=text-embedding-v3
export RUSTFS_ENDPOINT=http://localhost:19000
export RUSTFS_BUCKET=soarer-alert
```

本地默认 RustFS 凭据为 `rustfsadmin / rustfsadmin`，生产环境应通过环境变量注入
真实 AccessKey 和 SecretKey。

### 登录与生产边界

- 本地和生产环境都强制启用登录，使用 HttpOnly 服务端 Session Cookie，不再提供本地免登录模式。
- 系统不开放注册；首次启动通过 `AUTH_ADMIN_USERNAME` 和 `AUTH_ADMIN_INITIAL_PASSWORD` 引导创建管理员，管理员首次登录必须修改密码。
- 角色只有 `ADMIN` 和 `OPS`：`ADMIN` 可管理用户并使用全部业务功能；`OPS` 可使用聊天、文档、诊断和运维工作台，但不能访问用户管理。
- 聊天会话按用户隔离；文档和诊断任务团队共享，并记录创建者。
- 生产环境使用 `docker-compose.prod.yml`，只有应用业务端口映射到宿主机；
  PostgreSQL、Redis、RustFS、CLS-MCP 保留在 Docker 内网。
- 生产管理端口默认为容器内 `9911`，Prometheus 从 Docker 内网抓取
  `http://soarer-alert-app:9911/actuator/prometheus`，不对公网暴露。
- 生产环境建议通过 HTTPS 反向代理访问，并将 `AUTH_SESSION_COOKIE_SECURE=true`。


## 🚀 快速开始

### 0. 本地开发（推荐）

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
.\scripts\dev-infra.ps1

$env:DASHSCOPE_API_KEY="your-real-key"
$env:AUTH_ADMIN_USERNAME="admin"
$env:AUTH_ADMIN_INITIAL_PASSWORD="Local-Boot-123456"
.\scripts\dev-app.ps1
```

默认 `docker compose up` 只启动 PostgreSQL、Redis、RustFS 和 Prometheus，不会启动
`soarer-alert-app` 应用容器。应用直接运行在 Windows 宿主机；代码修改后只需要停止并重新执行
`dev-app.ps1`，不需要重建应用镜像。

服务默认端口：

| 服务 | 地址 | 用户 / 密码 |
|------|------|-------------|
| PostgreSQL + pgvector | `localhost:15432` | `superbiz / superbiz_local_password` |
| Redis | `localhost:16379` | 无密码 |
| RustFS S3 API | `http://localhost:19000` | `rustfsadmin / rustfsadmin` |
| RustFS 控制台 | `http://localhost:19001` | `rustfsadmin / rustfsadmin` |
| Prometheus | `http://localhost:19090` | 无 |

> 说明：PostgreSQL、Redis、RustFS 和 Prometheus 由 `docker-compose.yml` 统一管理。本地
> Prometheus 通过 `host.docker.internal:9900` 抓取宿主机 Java 进程指标。应用启动时 Flyway
> 会自动创建 `vector`、`hstore`、`uuid-ossp` 扩展、`vector_store` 向量表和业务持久化表。

### 容器化调试

```powershell
$env:DASHSCOPE_API_KEY="your-real-key"
$env:AUTH_ADMIN_USERNAME="admin"
$env:AUTH_ADMIN_INITIAL_PASSWORD="Local-Boot-123456"
docker compose --profile app up -d --build --wait
```

该模式会额外构建并启动 `soarer-alert-app` 容器，适合验证镜像、启动顺序和容器内网络。
应用与 CLS-MCP 一起容器化运行时使用：

```powershell
docker compose --profile app --profile mcp up -d --build --wait
```

增加 Loki 与 Promtail：

```powershell
docker compose --profile app --profile observability up -d
```

应用镜像由 `Dockerfile` 使用 Java 25 运行时构建。健康检查、启动顺序、Prometheus 采集和日志采集配置见 [docs/OPERATIONS.md](docs/OPERATIONS.md)。

生产部署使用独立 Compose 文件，敏感变量必须在当前 shell 或部署平台的 Secret 中提供：

```powershell
docker compose -f docker-compose.prod.yml up -d --build --wait
```

生产 Compose 会强制启用生产 profile、MCP、CLS 上传和 API 认证，并禁用 Prometheus/CLS Mock；
缺少必需密钥或令牌时会在启动阶段失败。

### 可选：接入腾讯云 CLS-MCP

项目默认使用本地 Mock 日志，便于无腾讯云凭证时演示。真实日志查询通过 `cls-mcp-server` 的 SSE 模式接入，Agent 会获得以下 MCP 工具：

- `SearchLog`：查询腾讯云 CLS 日志。
- `DescribeTopics`：查看日志集和日志主题。
- `TextToSearchLogQuery`：把自然语言转换为 CLS 查询语句。

启用前需要在腾讯云创建只读子账号并授权 `SearchLog`、`DescribeTopics`、`DescribeLogsets`、`DescribeIndex`、`DescribeLogHistogram`。完整部署、验收和降本说明见 [docs/OPERATIONS.md](docs/OPERATIONS.md)。密钥只放在当前 shell 或部署平台的 Secret 中，不要写入 `.env`、Compose 或文档。

启用命令形态：

```powershell
$env:DASHSCOPE_API_KEY="your-real-key"
$env:AUTH_ADMIN_USERNAME="admin"
$env:AUTH_ADMIN_INITIAL_PASSWORD="Local-Boot-123456"
$env:TENCENTCLOUD_SECRET_ID="your-secret-id"
$env:TENCENTCLOUD_SECRET_KEY="your-secret-key"
$env:MCP_CLIENT_ENABLED="true"
$env:CLS_MOCK_ENABLED="false"

docker compose --profile app --profile mcp up -d --build --wait
```

容器化应用默认 MCP 连接地址是 `http://cls-mcp:3000/sse`；宿主机 Java 进程默认连接
`http://localhost:13000/sse`。本地推荐命令：

```powershell
$env:TENCENTCLOUD_SECRET_ID="your-secret-id"
$env:TENCENTCLOUD_SECRET_KEY="your-secret-key"
.\scripts\dev-infra.ps1 -WithMcp

$env:DASHSCOPE_API_KEY="your-real-key"
$env:AUTH_ADMIN_USERNAME="admin"
$env:AUTH_ADMIN_INITIAL_PASSWORD="Local-Boot-123456"
$env:MCP_CLIENT_ENABLED="true"
$env:CLS_MOCK_ENABLED="false"
.\scripts\dev-app.ps1
```

### 1. 环境准备

`scripts/dev-app.ps1` 默认使用 `JAVA_HOME` 或 PATH 中的 Java，并自动设置
`SPRING_PROFILES_ACTIVE=local`、PostgreSQL、Redis、RustFS、Prometheus 和 CLS-MCP 的
宿主机地址。需要在当前 PowerShell 会话提供真实百炼密钥和管理员初始化变量。

### 2. 启动应用

推荐直接使用：

```powershell
$env:DASHSCOPE_API_KEY="your-real-key"
$env:AUTH_ADMIN_USERNAME="admin"
$env:AUTH_ADMIN_INITIAL_PASSWORD="Local-Boot-123456"
.\scripts\dev-app.ps1
```

也可以手动启动：

```powershell
$env:JAVA_HOME="<path-to-jdk-25>"
$env:Path="$env:JAVA_HOME\bin;$env:Path"
$env:SPRING_PROFILES_ACTIVE="local"
mvn -s .mvn\maven-settings.xml spring-boot:run
```


### 3. 使用示例

**Web 界面**
```
http://localhost:9900
```

**命令行**
```bash
# 上传文档
curl -X POST http://localhost:9900/api/upload \
  -F "file=@document.txt"

# 智能问答
curl -X POST http://localhost:9900/api/chat \
  -H "Content-Type: application/json" \
  -d '{"Id":"test","Question":"什么是向量数据库？"}'

# 向量库健康检查
curl http://localhost:9900/vector-store/health

# 对象存储健康检查
curl http://localhost:9900/object-store/health
```

### 4. 重新索引说明

Milvus 中的历史向量不做直接迁移。迁移到 pgvector 后，请重新上传或重新索引原始运维文档，确保使用同一个 Embedding 模型、1024 维向量和新的 metadata 结构。

## 📚 深入文档

| 文档 | 内容 |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 架构分层、业务链路、持久化模型 |
| [docs/OPERATIONS.md](docs/OPERATIONS.md) | 部署、监控、日志、备份与故障恢复 |
| [docs/TESTING.md](docs/TESTING.md) | 单元测试、冒烟测试、真实模型验收 |


**版本**: v1.0.0  
**作者**: chief  
**许可证**: MIT

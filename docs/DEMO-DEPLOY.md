# SoarerAlert 演示部署手册

面向"低带宽轻量服务器 + Mock 数据"的演示场景。服务器上只需要安装 Docker；
如需域名 HTTPS 访问，再额外安装 Nginx 和 certbot。不需要安装 JDK、Maven、
Node.js 或任何数据库。

**演示模式特性：**

- Prometheus 告警和腾讯云 CLS 日志全部使用 Mock 数据
- 不需要任何腾讯云子账号、SecretKey 或日志主题
- AI 对话和 RAG 仍然调用真实的阿里云百炼模型（需要 `DASHSCOPE_API_KEY`）
- 所有宿主机端口默认只绑定 `127.0.0.1`，公网访问建议通过 Nginx 反代到应用
- 应用镜像由本地预构建的 jar 生成，服务器上不执行 Maven，避免低带宽下载依赖

---

## 一、服务器准备（一次性）

### 1. 安装 Docker（Ubuntu 22.04 / 24.04）

```bash
curl -fsSL https://get.docker.com | bash
sudo systemctl enable --now docker
docker compose version   # 确认 compose 插件可用
```

### 2. （可选）配置镜像加速

国内服务器如果 `docker pull` 超时，先配置加速器（腾讯云服务器优先使用自带源）：

```bash
sudo mkdir -p /etc/docker
sudo tee /etc/docker/daemon.json <<'EOF'
{
  "registry-mirrors": ["https://mirror.ccs.tencentyun.com"]
}
EOF
sudo systemctl restart docker
```

### 3. 防火墙只放行需要的端口

在云控制台的防火墙 / 安全组中只放行：

| 端口 | 用途 |
|---|---|
| 22 | SSH |
| 80 | Nginx HTTP 入口（可重定向到 HTTPS） |
| 443 | Nginx HTTPS 入口 |

**不要**放行 15432、16379、19000、19001、19090——演示 Compose 已把它们绑定到
`127.0.0.1`，即使误放行，公网也访问不到。

---

## 二、本地打包（Windows 开发机）

```powershell
cd <project-root>
.\scripts\build-demo.ps1
```

脚本会：

1. `mvn package -DskipTests` 构建 fat jar
2. 编译容器健康检查程序 `HealthCheck.class`
3. 组装 `dist\soarer-alert-demo\` 目录（Compose 文件 + 预构建镜像上下文 + 配置）
4. 压缩为 `dist\soarer-alert-demo.zip`（约 140 MB（主要是 fat jar））

> 代码有改动后，重新执行一次本脚本并重新上传 zip 即可。

## 三、上传到服务器

```powershell
scp <project-root>\dist\soarer-alert-demo.zip root@<服务器IP>:/opt/
```

3M 带宽约需 7-10 分钟。也可以用宝塔面板、SFTP 工具上传。

## 四、服务器启动

```bash
cd /opt
unzip soarer-alert-demo.zip
cd soarer-alert-demo

# 建议写入 .env，并执行 chmod 600 .env
cat > .env <<'EOF'
DASHSCOPE_API_KEY=sk-你的百炼密钥
AUTH_ADMIN_USERNAME=admin
AUTH_ADMIN_INITIAL_PASSWORD=Demo-Boot-123456
APP_BIND=127.0.0.1
AUTH_SESSION_COOKIE_SECURE=true
SERVER_FORWARD_HEADERS_STRATEGY=framework
CORS_ALLOWED_ORIGINS=https://你的域名
EOF
chmod 600 .env

# 首次会拉取基础镜像（约 300MB，3M 带宽约 15-20 分钟），之后构建秒级完成
docker compose --env-file .env -f docker-compose.demo.yml up -d --build --wait
```

`--wait` 会等所有容器健康检查通过。首次启动时应用需要执行 Flyway 建表，
大约 1-2 分钟。

## 五、验证

```bash
# 容器状态：5 个容器应该都是 healthy / running
docker compose -f docker-compose.demo.yml ps

# 应用健康检查
curl http://127.0.0.1:9900/actuator/health
# 期望输出包含 "status":"UP"

# 查看应用日志
docker logs -f soarer-alert-app
```

如果已按上文配置 Nginx，浏览器访问：

```
https://你的域名
```

使用 `AUTH_ADMIN_USERNAME` / `AUTH_ADMIN_INITIAL_PASSWORD` 登录。
首次登录会要求修改密码（按页面提示操作即可）。

演示 AI Ops：

1. 登录后进入聊天页或打开 `/ops.html`
2. 创建诊断任务，Agent 会用 Mock 告警 + Mock 日志跑完整多 Agent 诊断
3. 在工作台查看时间线、工具调用（`queryPrometheusAlerts`、`queryLogs` 等）和报告

## 六、日常运维

```bash
cd /opt/soarer-alert-demo

# 查看状态 / 日志
docker compose -f docker-compose.demo.yml ps
docker compose -f docker-compose.demo.yml logs -f app

# 停止（保留数据）
docker compose -f docker-compose.demo.yml down

# 启动（镜像已存在时不再拉取）
docker compose -f docker-compose.demo.yml up -d --wait

# 彻底重置演示数据（清空数据库、向量库、上传文件）
docker compose -f docker-compose.demo.yml down -v
```

## 七、更新到新版本

本地：

```powershell
cd <project-root>
.\scripts\build-demo.ps1
scp dist\soarer-alert-demo.zip root@<服务器IP>:/opt/
```

服务器：

```bash
cd /opt
unzip -o soarer-alert-demo.zip
cd soarer-alert-demo
export DASHSCOPE_API_KEY="sk-..."
export AUTH_ADMIN_USERNAME="admin"
export AUTH_ADMIN_INITIAL_PASSWORD="当前管理员初始密码变量"
docker compose -f docker-compose.demo.yml up -d --build --wait
```

数据保存在 Docker 卷中，更新版本不会丢失已上传的文档和诊断记录。

## 八、常见问题

| 现象 | 处理 |
|---|---|
| `docker pull` 超时 | 按第一章配置镜像加速器后 `systemctl restart docker` |
| 应用一直 restarting | `docker logs soarer-alert-app`，最常见是 `DASHSCOPE_API_KEY` 未设置或无效 |
| 页面打不开 | 确认云防火墙已放行 80/443；先在服务器上执行 `curl http://127.0.0.1:9900/actuator/health` 和 `curl -k https://127.0.0.1/actuator/health` |
| 4G 内存吃紧 | 演示 Compose 已限制各容器内存（app 1.5G、其余合计约 1.4G）；建议服务器加 2G swap |
| 想重新演示首次登录 | `docker compose -f docker-compose.demo.yml down -v` 后重新启动 |

## 九、安全提醒

- `DASHSCOPE_API_KEY` 只放在权限为 `600` 的 `.env` 中，不要写进 zip、Compose 或文档
- 演示结束不用时，执行 `docker compose -f docker-compose.demo.yml down` 停止，
  避免百炼 API 被意外调用产生费用
- 未配置 HTTPS 时，演示站是 HTTP 明文传输，不要在里面放真实密钥或敏感运维文档

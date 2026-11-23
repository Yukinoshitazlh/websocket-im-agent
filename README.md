# WebSocket 即时聊天 + AI Agent 系统

基于 **JDK 21 + Spring Boot 3.3 + Netty 4 + MySQL 8.0 + Redis 7 + RabbitMQ 3.13** 的分布式即时聊天与 AI Agent 协同后端系统。

详细架构图、协议规范、Redis/MQ 拓扑以及 6～8 周分阶段开发计划见：[docs/DESIGN.md](docs/DESIGN.md)。

## 项目结构

```text
.
├── docs/
│   └── DESIGN.md               # 系统架构、协议规范与 6～8 周开发路线
├── sql/
│   └── init.sql                # MySQL 8.0 建表与预置数据脚本
├── docker-compose.yml          # Redis 7 + RabbitMQ 3.13 (+ 可选 Qdrant) 编排
├── pom.xml                     # 父工程（统一依赖与 JDK 21 编译配置）
├── im-common/                  # 公共协议帧、Payload DTO、MQ 常量、RedisKey、JWT 工具
├── im-gateway/                 # Netty WebSocket 长连接网关（默认 WS :9001）
├── im-server/                  # 核心 IM 业务与消息路由服务（默认 HTTP :8080）
└── im-agent/                   # 独立 AI Agent Worker（ReAct 循环 / 流式 / 审批，:8082）
```

## 本地快速启动

### 1. 启动中间件（Redis + RabbitMQ）
```powershell
docker compose up -d
# 阶段 3 需要开启向量库 Qdrant 时：
docker compose --profile rag up -d
```
* RabbitMQ 管理后台：`http://localhost:15672`（默认账号 `im_admin` / `im_mq_123456`）

### 2. 初始化本机 MySQL 8.0 数据库
```powershell
& "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -u root -p < sql\init.sql
```
复制 `.env.example` 为 `.env`（或在各模块 `application.yml` 中配置你的 MySQL 密码）。

### 3. 在 IntelliJ IDEA 中打开项目
1. 打开根目录，自动识别多模块 Maven 工程。
2. 确认 `Project Structure -> Project SDK` 为 **JDK 21 (Eclipse Temurin)**。
3. 依次启动 `ServerApplication`（8080）、`GatewayApplication`（WS 9001）、`AgentApplication`（8082）。

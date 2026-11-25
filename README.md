# PulseIM — 分布式 WebSocket 即时聊天 + AI Agent 协同系统

基于 **JDK 21 + Spring Boot 3.3 + Netty 4 + MySQL 8.0 + Redis 7 + RabbitMQ 3.13 + WebFlux + Vue 3 / TypeScript / Vite** 构建的分布式即时聊天与 AI Agent 工程化落地项目。

* 架构设计、协议规范与 6～8 周演进路线：[docs/DESIGN.md](docs/DESIGN.md)
* 简历 STAR 模板与大厂面试 17 问深度复盘：[docs/INTERVIEW_GUIDE.md](docs/INTERVIEW_GUIDE.md)

---

## 核心工程特性

* **Netty 长连接网关与多节点精准路由（`im-gateway`）**：HTTP Upgrade 前置 JWT 握手鉴权、`IdleStateHandler` 读空闲心跳剔除、JDK 21 虚拟线程卸载阻塞业务调用、防顶号误删的 Redis 在线路由表、零落库纯内存 `TYPING` 实时输入态信令，以及基于 RabbitMQ Direct Exchange 节点专属队列的跨网关精准推送。
* **高可靠消息底座与完整聊天体验（`im-server`）**：Redis 幂等缓存 + MySQL 唯一索引双重防重；Lua 原子 `INCR` + 分布式锁冷启动恢复的会话递增 `seq`；`TransactionTemplate` 事务提交后异步投递 MQ；基于 `(conversation_id, seq)` 的断线增量同步与 **`beforeSeq` 游标分页（避免深翻页 OFFSET 扫描）**；支持 2 分钟内消息撤回（`MSG_RECALL` 广播）、引用回复批量关联、实时在线状态与会话历史检索。
* **深度融合 AI Agent 引擎与延迟调度（`im-agent` + `im-server`）**：通过 RabbitMQ（含死信队列 DLQ）解耦长耗时推理与 IM 主链路；手写有界 `max_steps` ReAct 循环与双模流式客户端；支持 Fanout 控制广播 + `Disposable` 毫秒级中途打断、防 Prompt 注入的会话权限隔离历史检索、**Human-in-the-Loop 审批挂起/唤醒状态机**，以及 **Redis ZSet 延迟队列（Lua 原子弹出 + DB 定时兜底 + 乐观 CAS 防重）** 实现群日程提醒自动到点推送。
* **富媒体与对象存储秒传（`im-server` + `im-web`）**：浏览器端 WebCrypto 计算文件 SHA-256 哈希，命中 `im_file_object` 唯一索引即零流量秒传；支持 MinIO S3 对象存储（可选 `--profile storage` 启动，端口 `9100`/`9101`）并内置本地磁盘存储自动降级（`AUTO` 模式）。
* **工程化前端工作台与可观测（`im-web`）**：独立 **Vue 3 + TypeScript + Vite + Pinia** SPA（构建直接产物至 `im-server/src/main/resources/static/`），支持浅色/暗黑主题、Markdown 代码高亮与一键复制、IndexedDB 本地消息秒开缓存、剪贴板粘贴/拖拽发图、灯箱预览、心跳 RTT 监控以及 Node.js 24 原生并发压测工具。

---

## 仓库目录结构

```text
.
├── docs/
│   ├── DESIGN.md               # 系统架构图、时序图、协议规范与 6～8 周路线
│   └── INTERVIEW_GUIDE.md      # 简历 STAR 话术与面试高频 15 问复盘
├── scripts/
│   └── load-test.mjs           # Node.js 24 原生 WebSocket 并发长连接与吞吐压测脚本
├── sql/
│   └── init.sql                # MySQL 8.0 完整 8 表建表（含 im_file_object）与预置数据
├── docker-compose.yml          # Redis 7 + RabbitMQ 3.13 (+ 可选 MinIO / Qdrant)
├── pom.xml                     # 父工程（JDK 21 + Lombok + Spring Boot 3.3.5）
├── im-common/                  # 公共协议帧、Payload DTO、MQ 事件、RedisKeys、JWT 工具
├── im-gateway/                 # Netty WebSocket 网关（WS :9001，管理口 :8081）
├── im-server/                  # 核心 IM 业务、限流、文件秒传、延迟提醒调度与静态托管（HTTP :8080）
├── im-agent/                   # 独立异步 AI Agent Worker（管理口 :8082）
└── im-web/                     # Vue 3 + TypeScript + Vite + Pinia 前端工程（开发 :5173）
```

---

## 本地快速运行

### 1. 启动中间件（Redis 7 + RabbitMQ 3.13，可选 MinIO）
```powershell
docker compose up -d
# 如需启用 MinIO 对象存储（API :9100 / 控制台 :9101，未启动时自动降级本地 ./uploads/ 存储）：
docker compose --profile storage up -d
# RabbitMQ 管理后台：http://localhost:15672 （默认账号 im_admin / im_mq_123456）
```

### 2. 初始化 MySQL 8.0 数据库与环境变量
```powershell
mysql --default-character-set=utf8mb4 -u root -p < sql/init.sql
```
复制 `.env.example` 为 `.env`，填入你的 MySQL 密码（如需对接真实大模型，同步填入 `LLM_BASE_URL`、`LLM_API_KEY`、`LLM_MODEL`）。

### 3. 启动后端模块（IntelliJ IDEA，JDK 21）
1. 启动 `ServerApplication`（`:8080`，已内置打包好的 Vue SPA）
2. 启动 `GatewayApplication`（WS `:9001`，HTTP `:8081`）
3. *(可选双网关集群验证)* 复制配置启动 `GatewayApplication-2`，设置环境变量：
   `GATEWAY_NODE_ID=gateway-2;WS_PORT=9002;GATEWAY_HTTP_PORT=8089`
4. 启动 `AgentApplication`（`:8082`）
5. 浏览器直接打开 **http://localhost:8080/**（无需再单独起前端）；如需前端热更新开发，可在 `im-web/` 目录执行：
   ```powershell
   npm install
   npm run dev    # 访问 http://localhost:5173（自动代理 /api 与 /uploads 到 :8080）
   npm run build  # 重新打包输出至 im-server/src/main/resources/static/
   ```

---

## 并发压测与性能优化实测报告

在本地开发机上同时运行 `im-server` + 双网关集群（`gateway-1:9001` & `gateway-2:9002`）+ MySQL 8.0 + Docker（Redis 7 & RabbitMQ 3.13），使用 [scripts/load-test.mjs](scripts/load-test.mjs) 实测 100% 跨网关真实落库与推送链路：

| 压测场景 | 并发连接 / 建连 P99 | 送达率 / 异常数 | 落库+推送吞吐 | `SERVER_ACK` P99 | 端到端跨网关 P99 |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **优化前基线**（200 并发 × 5 条） | `200/200` (`43.6 ms`) | `100%` / `0` | `604.8 msg/s` | `832.7 ms` | `778.3 ms` |
| **优化后复测**（200 并发 × 5 条） | `200/200` (`34.4 ms`) | `100%` / `0` | **`1,072.2 msg/s`** (`+77.3%`) | **`92.3 ms`** (`-88.9%`) | **`175.9 ms`** (`-77.4%`) |
| **优化后极限压测**（500 并发 × 3 条） | `500/500` (`38.3 ms`) | `100%` / `0` | **`1,447.4 msg/s`** | **`298.7 ms`** | **`651.6 ms`** |

核心调优手段（详见 [docs/INTERVIEW_GUIDE.md](docs/INTERVIEW_GUIDE.md) Q16）：
1. **热路径 Cache-Aside 缓存**：会话成员 Set 与用户昵称接入 Redis 缓存并精简发送者 DB 查询，单条消息 MySQL 往返由 **7 次降至 2 次（`-71%`）**；
2. **连接池升级**：`im-server` HikariCP 扩容至 40 并开启服务端预编译语句缓存，`im-gateway` 引入 **Apache HttpClient 5（单路由 256 Keep-Alive 长连接）**；
3. **根治 JDK 21 虚拟线程 Pinning**：将事务提交后的未读递增与 RabbitMQ 发布卸载至专用平台线程池 `pushDispatchExecutor`，规避 `amqp-client` `synchronized` 导致的载体线程固定。

```powershell
# 运行全模块单元测试（11 个单测覆盖 JWT、分布式锁 Seq、双重幂等、差异化限流、延迟队列提醒 CAS、防注入权限隔离与实时断流）
mvn test

# 运行真实 WebSocket 并发长连接与跨用户端到端压测（Node.js 24+，零依赖）
node scripts/load-test.mjs --users=200 --msgs=5 --ws=ws://127.0.0.1:9001/ws,ws://127.0.0.1:9002/ws
```

# FE14：Nginx 独立前端部署与最终验收

日期：2026-10-18。采用开发文档 FE14 方案 B，仅补齐构建、部署与验收；保留工作区既有 FE00–FE13、后端、benchmark 等未提交改动。未实现新的后端业务接口，未接入 Benchmark 报告页面。

## 完成内容与部署边界

- `frontend/Dockerfile` 使用 Node 24 + `npm ci` 构建，运行层仅包含 Nginx、静态产物及服务器配置。生产入口即使误设 `VITE_USE_MSW=true` 也不会启动 MSW；镜像删除 Mock worker。
- Nginx 同源代理 `/api/`、`/ws/` 到 `backend:8080`，保留路径、query、请求体与后端状态码；不把接口错误回退成 HTML。REST 读写超时 120 秒，WebSocket 使用 HTTP/1.1、Upgrade、3600 秒读写超时及关闭缓冲。保留原始 Host/Origin，兼容 Spring 同源校验。
- Nginx 提供 React Router 刷新回退、哈希资源长期缓存、HTML/API 不缓存、静态缺失 404、21 MB multipart 请求上限和独立 `/healthz`。后端的文件限制仍为 20 MB，multipart 总量 21 MB；若修改后端限制，应同步调整代理。
- 根 Compose 增加 backend/frontend、健康启动顺序、文档持久卷和服务器 secret。后端镜像仅打包现有 Java 21 应用，内部连接覆盖为 Compose 服务 DNS；未更改 Controller/DTO/Agent。
- 修正 Qdrant 镜像缺少 wget 导致的健康检查失败；Redis 健康检查显式注入密码并验证 PONG。现有数据卷保留。
- `scripts/initialize-compose.ps1` 只在缺失时创建根 `.env` 和随机令牌文件，不显示秘密，不覆盖用户配置。Nginx 从 secret 文件生成仅服务器可读配置，Spring 从 configtree 读取同一令牌；没有任何密钥进入 `VITE_*`、浏览器 JS 或 URL。
- 默认 Web 地址为 `http://localhost:8088`，仅绑定本机。当前后端共享 token 不是多用户身份体系；公网接入需要有用户授权的 HTTPS 网关。基础设施端口沿用现有开发设置。
- 保留 Vite 的开发代理。新增独立部署验收配置，直接访问 Nginx，无 Vite、MSW、REST/WS 拦截。真实工作流额外断言服务器订阅确认和实际文档/Agent 事件，不能用 REST 轮询冒充实时进度。

所有页面仍复用原有 loading/empty/error/success，REST 仍集中在 `src/api`，服务端状态仍由 TanStack Query 管理。未增加页面内 fetch/axios、隐藏思维链展示或不可信 HTML 执行。

## 启动与维护

```powershell
cd D:\Project\ai-document-agent
./scripts/initialize-compose.ps1
# 在根 .env 配置有效的数据库凭据、Chat Key、Embedding Key。
# 默认 Chat 使用 OPENAI_API_KEY；默认 Embedding 使用 DASHSCOPE_API_KEY。
# 可用 RAG_CHAT_API_KEY / VECTOR_EMBEDDING_API_KEY 覆盖，URL/模型需匹配实际供应商。
docker compose config --quiet
docker compose up -d --build
docker compose ps
```

打开 [Web UI](http://localhost:8088)。已构建版本可运行 `docker compose up -d`；修改源码后必须重建。新部署使用 `.env.example` 中的配置；原 `.env` 不会自动合并新增项，尤其要检查 Embedding 供应商凭据和 Endpoint。不要把根 `.env` 复制到前端。

```powershell
docker compose logs --tail=100 backend frontend
docker compose exec frontend nginx -t
docker compose stop frontend backend
docker compose up -d
```

日志仅用于本地排障；不要公开包含运行时配置的完整 `docker compose config` 或 `nginx -T`。`nginx -t` 仅检查语法，不输出令牌。日常停止服务使用 `stop`；如使用 `down` 保留卷，避免 `down -v` 删除验收数据。

默认令牌文件为 `secrets/progress_subscription_token`。自定义 secret 文件时设置根 `.env` 的 `PROGRESS_TOKEN_FILE` 并自行提供 32–128 位仅含字母、数字、`_`、`-` 的文件。更换令牌后重建两个容器，确保挂载内容和 Nginx 生成配置一致。生成脚本及默认文件无换行；不要给文件加入 BOM 或其他空白。

仅启动开发基础设施：`docker compose up -d mysql redis rabbitmq qdrant elasticsearch`。开发代理目标保持 `VITE_DEV_BACKEND_URL=http://localhost:8080`；开发 WebSocket 令牌仍按 FE08 放在 Vite 服务器进程环境，不在 `VITE_*` 中。WebSocket 代理保留 Host，匹配浏览器 Origin，避免 Spring 同源校验拒绝；REST 代理继续使用原配置。

如需让 Vite 直接连接当前 Compose，可在启动 Vite 的进程中设置公开的 `VITE_DEV_BACKEND_URL=http://127.0.0.1:8088`；此时 Nginx 负责服务器 token 注入。不要把 Vite 开发服务公开到公网。

## 接口核对与后端缺口

核对依据：开发文档 FE14、当前 `KnowledgeBaseController`、`DocumentController`、`DocumentIngestionController`、`ConversationController`、`ExecutionController`、`ProgressWebSocketConfiguration` / Handler / HandshakeInterceptor / SubscriptionService。开始时 `http://127.0.0.1:8080/v3/api-docs` 连接被拒绝，因此不把规划文档当成已部署 OpenAPI。

| 接口                                                        | 用途/契约                                                                                          |
| ----------------------------------------------------------- | -------------------------------------------------------------------------------------------------- |
| `/api/v1/knowledge-bases`、`/{kbId}`                        | 现有知识库 CRUD/分页                                                                               |
| `/api/v1/knowledge-bases/{kbId}/documents`、`/{documentId}` | multipart 上传、分页、详情、删除                                                                   |
| `POST .../documents/{documentId}/ingestion/retry`           | 失败重试                                                                                           |
| `POST /api/v1/knowledge-bases/{kbId}/query`                 | 快速 RAG                                                                                           |
| `/api/v1/knowledge-bases/{kbId}/conversations`              | 创建/查询会话                                                                                      |
| `/api/v1/conversations/{id}/messages`                       | 同步提问与历史分页                                                                                 |
| `DELETE /api/v1/conversations/{id}`                         | 删除会话                                                                                           |
| `POST /api/v1/agent/executions`                             | `{knowledgeBaseId, conversationId, agentMode, question}`，`Idempotency-Key`                        |
| `GET /api/v1/agent/executions/{uuid}?knowledgeBaseId=...`   | 权威执行状态及步骤                                                                                 |
| `POST .../{uuid}/resume                                     | cancel?knowledgeBaseId=...`                                                                        | 已有恢复/取消动作 |
| `WS /ws/progress`                                           | 原生 JSON；`SUBSCRIBE/UNSUBSCRIBE` + resource + knowledgeBaseId + 资源 ID；握手由服务器注入 Bearer |
| 后端内部 `GET /actuator/health`                             | Compose 健康检查，禁用 details；不通过 Nginx 对外代理                                              |
| 前端 `GET /healthz`                                         | 仅 Nginx 存活，不代表后端或模型已就绪                                                              |

已知后端缺口保持明确错误/限制，不在 FE14 编造字段或 Mock 成生产成功：

1. `GET /api/v1/agent/executions` 列表缺失，Dashboard/任务列表可能报错；带 UUID + KB ID 的详情可独立恢复。
2. 原文件 `download`、文档 `chunks`、Chunk `context` 尚无 Controller；Context 响应 DTO 也未定义。Citation 可展示真实 quote/文档/Chunk ID，相邻上下文与文档预览/下载仍受阻。
3. `/api/v1/system/health` 未实现，保留 UI 错误，不能用 Nginx `/healthz` 冒充组件健康。
4. 异步 Execution 轮次及消息关联尚未写入会话历史；聊天整页刷新无法恢复这些执行关联。保存独立任务详情 URL 后刷新可重新读取权威状态/步骤；普通同步会话历史由后端持久化。SPA 回退只解决路由，不补齐持久化。

## 文件与依赖

新增：根 `Dockerfile`、`.dockerignore`；`frontend/Dockerfile`、`.dockerignore`、`nginx/default.conf.template`、`nginx/40-render-config.sh`；`frontend/playwright.deployment.config.ts`、`frontend/e2e/deployment.spec.ts`；`scripts/initialize-compose.ps1`；本记录。

修改：根 `compose.yaml`、`.env.example`、`.gitignore`；`frontend/package.json`、`README.md`、`src/main.tsx`、`src/app/router.tsx`、`vite.config.ts`、`playwright.config.ts`、`e2e/live-workflow.spec.ts`；`docs/development-progress.md`。

无 npm 依赖新增或升级，`package-lock.json` 不变。新增镜像依赖为 `node:24-alpine`（仅构建）、`nginx:stable-alpine`、`maven:3.9-eclipse-temurin-21`（仅构建）、`eclipse-temurin:21-jre-alpine`。服务端构建使用 Maven 包装已有应用，未执行或宣称后端测试；本轮范围是 FE14。

## 实际验证

以下均为本轮实际执行；浏览器 Mock 回归与真实容器验收分别记录。

| 命令                                                                       | 实际结果                                                                                  |
| -------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------- |
| `./scripts/initialize-compose.ps1`（执行两次）                             | 首次创建随机 secret；第二次保留既有 `.env` / secret，幂等检查通过                         |
| PowerShell AST 解析初始化脚本                                              | 通过                                                                                      |
| `docker compose config --quiet`、`docker compose config --services`        | 通过，7 个服务                                                                            |
| `npm run typecheck`                                                        | 通过                                                                                      |
| `npm run lint`                                                             | 通过，0 warning                                                                           |
| `npm run test -- --maxWorkers=1 --no-file-parallelism --testTimeout=60000` | 30 个文件、156/156 通过；MSW/服务 Mock，191.28 秒                                         |
| `npm run build`                                                            | 通过；3608 模块，保留原有大于 500 kB bundle 提示                                          |
| `docker compose build frontend backend`                                    | 两个真实镜像构建通过；后端 Maven package 不包含测试                                       |
| `docker compose build frontend`、`docker compose up -d --no-deps frontend` | 最终前端源码重建/启动通过；JS 1731.05 kB / gzip 540.25 kB                                 |
| `docker compose up -d`、`docker compose ps`                                | 完整启动通过，7/7 healthy；现有持久卷保留                                                 |
| `docker compose exec frontend nginx -t`                                    | 实际生成配置语法通过                                                                      |
| 容器内检查静态目录/secret                                                  | 无 `.env`、无 Mock worker、静态文件内未找到服务器进度令牌                                 |
| 容器内请求 `/v3/api-docs`                                                  | HTTP 404；当前应用没有可用 OpenAPI endpoint，接口核对以 Java 实现为准                     |
| `npm run test:e2e -- --project=chat --workers=1 --reporter=line`           | 11/11 通过，43.9 秒；明确使用 REST/WS Mock                                                |
| `npm run test:e2e -- --workers=1 --reporter=line`（隔离输出后）            | 全部 12/12 通过，43.8 秒；含开发 MSW 场景与 REST/WS Mock                                  |
| `npm run test:deployment` 首次运行                                         | 3 项真实 Nginx/REST/WS 通过、1 项业务工作流失败、1 项 Resume 缺少预置任务而 skip；非 Mock |

隔离输出后执行 `npm run test:deployment -- --grep Nginx`：3/3 真实浏览器检查通过，2.3 秒；报告位于 `frontend/playwright-report/deployment/index.html`。该过滤命令仅代表部署代理与静态站点通过，不代表完整模型工作流通过。

另启动真实开发代理：进程环境设置 `VITE_DEV_BACKEND_URL=http://127.0.0.1:8088`、`VITE_USE_MSW=false`，运行 `npm run dev -- --mode live --host 127.0.0.1 --port 5175 --strictPort`。以 `PLAYWRIGHT_BASE_URL=http://127.0.0.1:5175` 执行 `npm run test:deployment -- --grep '真实 WebSocket' --output test-results/dev-proxy --reporter=line`，修正前真实握手失败；仅把 `/ws` 的 `changeOrigin` 改为 false 后，1/1 通过（2.6 秒），包括外国 Origin 403 断言。没有使用 Mock 或关闭 Spring 同源校验。

首次真实业务验收创建了 KB `7`，TXT/PDF 为文档 `9`、`10`；均实际到达后端，但状态为 `FAILED`、`errorCode=EMBEDDING_AUTHENTICATION_FAILED`，120 秒内没有 READY。根 `.env` 尚无 DashScope/Embedding override Key。没有把失败标成成功；真实问答、Citation、复杂 Agent 与完整实时工作流需凭据有效后重跑。数据保留供排障，未删除既有业务数据。

Vite Host 修正后，最终再次执行 `npm run typecheck`、`npm run lint`、`npm run build` 和完整 `npm run test -- --maxWorkers=1 --no-file-parallelism --testTimeout=60000`，全部通过；最后一轮 Vitest 为 30 个文件、156/156、146.55 秒。前端镜像也再次构建并启动成功，静态产物不变；最终 Compose 配置和 `nginx -t` 检查通过，7/7 服务保持 healthy。选定文件的 Prettier 与 `git diff --check` 通过，lockfile 没有改动。

本机沙箱最初执行 Vitest/Vite 时出现 Windows `realpath` EPERM（分别位于 `spy.js` / `main.tsx`），没有执行用例/完成构建；随后在正常用户权限下重新执行并获得上表结果。Vitest 保留既有 Ant Design React 19/static message、旧 `destroyOnClose` 提示，未通过跳过测试隐藏这些提示。

首次并行执行部署套件与旧 Mock 套件时，旧配置清理了 `test-results` 根目录，导致部署失败 trace 的 ENOENT；业务失败原因另经真实 REST 确认为 Embedding 鉴权失败。已将 Mock 输出/报告移到 `test-results/mock` / `playwright-report/mock`，与 live/deployment 隔离；最终验收报告以隔离后重跑为准。

截至最后核对，根 `.env` 仍未提供有效的 Embedding Key，完整业务验收保持未通过；未把等待凭据改为跳过或 Mock 成功。补齐凭据后执行 `docker compose up -d --no-deps --force-recreate --wait backend`，然后在 frontend 执行 `npm run test:deployment`。临时 5175 Vite 验证服务已停止，完整 Compose 保留运行。

## 人工验收

1. 配置根 `.env`，执行初始化、`docker compose up -d --build`，确认 `docker compose ps` 全部健康。浏览器打开 Web UI，Network 中请求保持 Web 同源；不得包含模型 Key 或进度 token。
2. 新建知识库，上传 `frontend/e2e/fixtures/idempotency.txt`，可另上传 PDF；观察上传队列、真实处理阶段和 READY。DevTools 的 `/ws/progress` 应收到 `SUBSCRIBED` 和文档事件；后端失败必须显示失败，不能只凭 `/healthz` 判定上传通过。
3. 创建 Retrieval-First 会话，询问“并发恢复发生冲突时客户端应该怎么办？请引用任务恢复验收资料。”；回答引用应来自上传文档，点击 Citation 核对真实 Chunk ID 和 quote。当前相邻上下文缺口应如实提示。
4. 保存会话地址并刷新，核对同步消息和 Citation 从真实历史恢复。直接输入文档/聊天/任务详情深链接，确认 Nginx 返回应用，缺失 `/assets/*.js` 应为 404。
5. 创建 Plan-Execute-Reflect 会话，询问“结合资料，总结幂等检查、暂停与并发恢复的步骤。”；查看结构化步骤和工具摘要，确认真实 WS 步骤事件；不应展示模型隐藏思维链。
6. 点击“打开任务详情”，保存含 `knowledgeBaseId` 的稳定 URL；运行中和终态分别刷新，核对 REST 状态与步骤恢复。聊天页 Execution 关联刷新限制仍需后端补齐，不能勾选完全通过。
7. 预置可恢复任务后检查 Resume/Cancel。已有手动按钮、409 和服务端状态机保持原行为；重启 backend/frontend 后检查任务详情与已上传文档记录仍可读取。
8. 自动验收：在 frontend 执行 `npm run test:deployment`。自定义端口时设置 `PLAYWRIGHT_BASE_URL`；额外 Resume 测试用 `PLAYWRIGHT_RESUME_EXECUTION_ID`、`PLAYWRIGHT_RESUME_KB_ID`，未提供会报告 skip。缺失后端/模型/引用/实时帧时工作流应失败，不能转为 Mock 后声称通过。

## 2026-10-19：本机与 Docker 后端同时消费导致文件读取失败

若文档报 `INGESTION_TRANSIENT_FAILURE`，且根异常是 Windows 路径下的 `NoSuchFileException`，应先核对是否同时运行了 IDEA 本机后端与 Compose 后端。Compose 的上传文件保存在 `document_data` 卷的 `/app/data/documents`，本机后端默认读取项目的 `data/documents`；共用数据库和 RabbitMQ 并不会同步这两个目录。

使用 `http://localhost:8088` 时，Nginx 将上传请求交给 Docker 后端。此时应停止 IDEA 中重复运行的本机后端，保留 Docker 后端，然后在失败文档上点击“重新处理”；无需重新上传或修改正常的 TXT 编码。可用 `docker exec ai-agent-rabbitmq rabbitmqctl list_consumers` 核对消费实例，并确认它们都能访问同一套原始文件。

需要多实例消费时，所有实例必须共享文档存储；若需要两套独立开发环境，则应隔离数据库、消息队列和存储。不要仅通过复制某一个缺失文件来维持两套目录。

## 配置依据

代理头/长连接按 [Nginx WebSocket 文档](https://nginx.org/en/docs/http/websocket.html)，路径转发按 [Nginx proxy_pass 文档](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_pass)，健康启动顺序按 [Docker Compose 文档](https://docs.docker.com/compose/how-tos/startup-order/) 核对。

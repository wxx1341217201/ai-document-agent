# ai-document-agent 前端

FE00–FE14 的 React + TypeScript + Vite 前端实现：知识库、文档、RAG/Citation、多轮聊天、Agent 可视化与任务恢复、Dashboard、测试及 Nginx 独立部署。真实业务能力仍受下文记录的后端接口缺口限制。

## 界面设计

界面遵循根目录的 [DESIGN.md](../DESIGN.md)：米白画布、暖黑文字、轻量衬线标题、胶囊按钮和柔和渐变装饰。工作台、知识库卡片、文档、问答、Agent 任务及系统状态共享同一套主题，并支持手机导航抽屉和减少动态效果设置。

`src/styles/tokens.css` 定义 CSS 设计变量，`theme.ts` 配置 Ant Design；`layout.css`、`dashboard.css`、`workspace.css` 和 `responsive.css` 分别组织布局、工作台、业务组件和断点。调整颜色与圆角时优先使用这些变量。

Inter 字体随应用自托管，授权文件位于 `public/fonts/OFL-Inter.txt`。Waldenburg 是授权字体，仓库未包含其文件；标题以 300 字重声明，中文使用宋体类衬线字体回退。所有装饰由 CSS 绘制，无外部图片依赖。

2026-10-19 完成交互精简：工作台聚焦知识库和文档，诊断移入高级工具；首次提问自动建会话，高级会话仍可选择模式。运行详情、文档元信息、引用编号和回答技术信息按需展开。知识库统一卡片展示，单页隐藏分页，已有文档时收起上传区。验收结果、真实问答记录及后端限制见 [UI 与交互精简验收](../docs/ui-ux-simplification-acceptance.md)。

随后根据用户视觉反馈，首页恢复第一版的大标题、淡彩光晕、文档插画、入口卡片和品牌细节，保留已改善的问答与信息层级。后续设计保留首页的视觉完整度，详见 [首页视觉恢复与验收](../docs/ui-home-visual-refinement.md)。

## Docker Compose 部署（FE14，方案 B）

在仓库根目录执行以下命令。首次运行的脚本保留现有 `.env` 与令牌，只在缺失时创建；API Key 仅配置在根 `.env`，不要写入 `frontend/.env*` 或 `VITE_*`。

```powershell
cd D:\Project\ai-document-agent
./scripts/initialize-compose.ps1
# 编辑根 .env：数据库凭据、OPENAI_API_KEY、DASHSCOPE_API_KEY，或相应的后端覆盖项。
docker compose config --quiet
docker compose up -d --build
docker compose ps
```

访问 [Web UI](http://localhost:8088)。Compose 现在包含 Nginx 前端、Spring Boot 和原有五个基础设施服务；后端通过健康检查后启动前端，文档原件和数据库/索引使用独立持久卷。后续已构建的版本可直接 `docker compose up -d`；源码修改后使用 `--build`。

生产浏览器使用同源 `/api/v1`、`/ws/progress`。Nginx 保留 API 路径与 query，支持 multipart、WebSocket Upgrade、长连接及 React Router 深链接刷新；HTML 不缓存，带哈希资源长期缓存，缺失静态文件返回 404。生产入口禁止 MSW，Docker 构建排除所有本地 `.env*`，发布镜像不包含 Mock worker。

进度令牌由脚本生成在 Git 忽略的 `secrets/progress_subscription_token`，以 Docker secret 文件分别挂载到 Spring configtree 和 Nginx；Nginx 仅在服务器上注入 WebSocket 握手头。前端环境变量、JS、URL 和 HTTP 响应均不承载该令牌。当前后端使用共享访问授权，Compose 默认只在 `127.0.0.1` 发布 Web 端口；公网多用户部署仍需已有权限体系的网关及 HTTPS。

```powershell
cd frontend
npm ci
npm run typecheck
npm run lint
npm run test -- --maxWorkers=1 --no-file-parallelism --testTimeout=60000
npm run build
npm run test:deployment
```

`test:deployment` 直接测试已运行的 Nginx，**不启动 Vite，不拦截 REST/WS，不使用 MSW**；覆盖静态资源/SPA、真实 REST、上传/真实 Citation/PER 实时事件与任务详情刷新。自定义 Web 端口时先设置公开的 `PLAYWRIGHT_BASE_URL`，例如 `$env:PLAYWRIGHT_BASE_URL='http://127.0.0.1:8089'`。额外 Resume 用例需要预置可恢复任务的非机密 ID；未提供时会明确跳过。

完整文件清单、命令结果、接口差异及逐步人工验收见 [FE14 交付记录](../docs/fe14-deployment.md)。任务详情和普通会话可以通过服务端恢复；当前后端尚未持久化聊天中的异步 Execution 关联，不能把 Nginx 的刷新回退当成这项业务能力已经补齐。

## 本地运行

```powershell
cd D:\Project\ai-document-agent\frontend
npm install
npm run dev
```

只启动开发依赖时，在仓库根目录执行 `docker compose up -d mysql redis rabbitmq qdrant elasticsearch`，再按已有方式运行后端。Vite 的 `/api`、`/ws` 开发代理继续保留；WebSocket 保留 Host 以匹配 Spring 的同源校验。如需直连完整 Compose，可将 Vite 服务器进程的 `VITE_DEV_BACKEND_URL` 设为 `http://127.0.0.1:8088`，由 Nginx 注入进度令牌。

默认使用相对 `/api/v1` 地址，Vite 会将 `/api` 和 `/ws` 代理到 `http://localhost:8080`。公开配置见 [`.env.example`](.env.example)；不应在前端环境文件中放置任何密钥。

## 契约 Mock

将 `VITE_USE_MSW=true` 写入 `.env.local` 后运行开发服务器，可使用 MSW 的浏览器端契约 Mock 演示知识库、文档上传和失败重试流程。MSW 的生成 worker 已位于 `public/mockServiceWorker.js`。

当前 Java 后端已实现知识库 CRUD、文档上传/列表/详情/删除，以及异步摄取任务重试。前端同步实际 Controller 使用以下重试接口：

- `POST /api/v1/knowledge-bases/{kbId}/documents/{documentId}/ingestion/retry`

原始文件下载仍等待后端提供以下受控接口：

- `GET /api/v1/knowledge-bases/{kbId}/documents/{documentId}/download`

Mock 可完整演示 `FAILED → RETRYING`；真实服务会以服务端返回的文档状态为准并轮询。

## 验证

```powershell
npm run format:check
npm run typecheck
npm run lint
npm run test
npm run build
npm run test:e2e
```

## FE06：Conversation 与多轮 Chat

入口为知识库详情的“开始问答”，对应 `/knowledge-bases/:kbId/chat`；创建或打开会话后使用 `/knowledge-bases/:kbId/chat/:conversationId`。会话 ID 保留在 URL，重新打开或刷新时从后端列表及消息历史恢复，无需本地保存服务端消息。

已实现会话列表及加载更多、创建时选择 Retrieval-First / ReAct / Plan-Execute-Reflect、历史消息、Enter 发送 / Shift+Enter 换行、乐观用户消息、失败恢复草稿、Markdown 回答、真实引用 Drawer 和确认删除。已有 `RAG` 会话也可正常打开。小屏会话列表使用 Drawer。

所有请求由 `src/api/conversation.api.ts` 发出，服务端状态由 TanStack Query 管理。对接实际 Java `ConversationController` 的五个接口：

- `GET /api/v1/knowledge-bases/{kbId}/conversations?page=0&size=20`
- `POST /api/v1/knowledge-bases/{kbId}/conversations`，请求 `{ title?, agentMode }`
- `GET /api/v1/conversations/{id}/messages?page=0&size=100`（按后端升序分页读取全部历史）
- `POST /api/v1/conversations/{id}/messages`，请求仅 `{ content }`；响应 `{ userMessage, assistantMessage }`
- `DELETE /api/v1/conversations/{id}`

契约以现有 Controller / DTO / Repository 为准。Conversation 消息接口支持所有已实现模式的同步最终回答；FE06 使用该接口，FE07 已将 ReAct/PER 提交切换到 Execution，见下文。实时进度已由 FE08 接入。消息 DTO 没有耗时、degraded、步骤或工具摘要字段，因此界面只展示实际返回的消息、引用、模型和已知 token 总量。后端没有独立的会话详情 GET，书签会话会通过当前知识库的分页列表定位，定位成功前不请求消息。

发送请求超时为 60 秒，覆盖后端 Agent 默认 40 秒 deadline。不会自动重试发送 POST；请求失败会重新核对消息历史并恢复草稿。刷新正在等待的请求时，只能恢复服务端已完成持久化的轮次；执行任务恢复属于后续模块。

FE06 的 MSW 数据为**内存 Mock**，浏览器完整刷新会重置演示中新建的会话和消息。真实刷新持久化由后端数据库提供。Vitest 使用 MSW 验证契约和交互；FE06 Playwright 使用运行在测试进程中的 REST Mock 验证“创建 → 两轮消息 → 真正刷新浏览器 → REST 恢复 → 引用 → 删除”，Mock 数据独立于浏览器状态。这些测试不表示完成了真实后端或模型联调。

FE06 的独立 Playwright `chat` 项目在 5174 端口启动关闭浏览器 MSW 的 Vite 服务，由 Playwright REST Mock 接管请求；原有 `chromium` 项目继续使用 5173 的 MSW 演示服务：

```powershell
npm run test:e2e -- --project=chat
```

### 人工验收（真实后端）

1. 启动后端及其数据库、检索和模型依赖，保持 `VITE_USE_MSW=false`，运行 `npm run dev`。
2. 在含 READY 文档的知识库详情点击“开始问答”，新建 Retrieval-First 会话，确认 URL 含服务端会话 ID。
3. 输入问题，检查用户消息立即出现、等待期间不能重复发送；拿到回答后继续追问，确认历史和引用可见。
4. 点击 `[C1]` 和底部文件名，核对真实 documentId / chunkId / 页码 / quote，点击“查看文档”进入文档详情。
5. 完整刷新或重新打开该 URL，确认标题、固定模式及两轮用户/AI 消息从后端恢复；没有本地伪造的消息。
6. 新建 ReAct、Plan-Execute-Reflect 会话，确认模式固定；窄屏下通过“会话列表”按钮打开 Drawer。
7. 模型不可用时确认错误可见、草稿恢复，核对历史后再重发；取消一次删除，再确认删除，检查列表更新及当前会话退出。
8. 输入含 HTML 的文本，确认按纯文本/安全 Markdown 展示；错误 KB 下的会话 URL 应显示资源错误。

### FE06 文件清单与依赖

以下路径均相对 `D:\Project\ai-document-agent\frontend`。

新增文件：

- `src/api/conversation.api.ts`、`src/api/conversation.api.test.ts`
- `src/features/chat/ChatPage.tsx`、`src/features/chat/ChatThread.tsx`、`src/features/chat/ChatMessage.tsx`、`src/features/chat/CreateConversationModal.tsx`
- `src/features/chat/conversation.hooks.ts`、`src/features/chat/agent-modes.ts`、`src/features/chat/ChatPage.test.tsx`
- `src/test/mocks/conversation-fixtures.ts`
- `e2e/chat.spec.ts`

修改文件：

- `src/api/contracts.ts`、`src/api/query-keys.ts`
- `src/app/router.tsx`、`src/styles/index.css`
- `src/test/mocks/handlers.ts`、`src/test/mocks/fixtures.ts`
- `playwright.config.ts`、`README.md`

未新增或升级依赖，`package.json` 和 `package-lock.json` 未修改。复用已有 TanStack Query、Ant Design、react-markdown、MSW、Vitest 和 Playwright。

### 本次实际验证（2026-10-11）

| 实际命令                                                                                                                                      | 最终结果                                                                                 |
| --------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------- |
| `npm run typecheck`                                                                                                                           | 通过；最终构建再次执行了 typecheck                                                       |
| `npm run lint`                                                                                                                                | 通过，0 warning                                                                          |
| `npm run test -- --maxWorkers=1 --minWorkers=1 --testTimeout=60000 --reporter=dot --reporter=json --outputFile=test-results/fe06-vitest.json` | 8 个测试文件、26 项测试全部通过                                                          |
| `npm run build`                                                                                                                               | 通过，3577 个模块完成构建；Vite 提示 JS chunk 大于 500 kB（当前约 1.66 MB，gzip 520 kB） |
| `npm run test:e2e -- --workers=1 --reporter=line`                                                                                             | 3 项全部通过，含原有知识库/文档流程和 FE06 桌面/小屏流程                                 |
| `git diff --check -- frontend`                                                                                                                | 通过                                                                                     |

测试结果 JSON 位于 `test-results/fe06-vitest.json`，构建产物位于 `dist/`。自动化 REST 数据全部是 Mock；并未完成真实数据库、检索和模型的端到端联调。

初始沙箱中的 `npm run test` / `npm run build` 被 Windows `EPERM realpath` 阻断，浏览器测试还遇到沙箱缓存路径重定向。最终测试和构建在正常用户环境重跑通过，单进程运行避免此机器上并发 UI 测试的冷启动超时。中间暴露的测试定位和弹窗清理问题已修复后重新验证，未忽略失败用例。尝试读取 `http://localhost:8080/v3/api-docs` 时 4 秒超时，契约核对使用实际 Java 源码。

## FE07：统一 Agent Gateway

聊天提交统一调用 `AgentGateway.sendMessage`，返回 `AgentSubmitResult`。只有 Gateway 根据会话模式选择后端提交接口，ChatPage/ChatThread 不拼装两种 REST 请求，也不按模式选择 endpoint。

- Retrieval-First 和已有 RAG 会话：同步消息接口返回真实 `userMessage` / `assistantMessage`，更新 TanStack Query 消息缓存，再核对服务端历史。
- ReAct / Plan-Execute-Reflect：创建 Execution，将真实 `executionId` 绑定到提交前生成的 pending assistant message ID。应用内切换会话后关联继续有效，PENDING / RUNNING / PAUSED 期间禁止再次发送；提交 POST 完成并不代表 Agent 已完成。
- 客户端消息 ID 同时作为 Execution 的 `Idempotency-Key`。提交不自动重试，不回退到同步接口；请求失败恢复草稿，同一问题的手动重试复用该键，修改问题后生成新键。同步消息接口没有幂等键契约，仍需先核对历史后再重发。
- FE07 提供“刷新执行状态”按钮，通过 TanStack Query 读取权威 REST 状态；FE08 已增加实时订阅和自动轮询。只展示状态、任务 ID、最终回答及有效的真实引用。仅 SUCCEEDED 且 `result.status=COMPLETED` 时展示回答；缺失/非法回答、失败、取消和暂停都有明确反馈。
- Execution 回答复用安全 Markdown 与 Citation Drawer，没有伪造 ConversationMessage 的 ID、sequenceNo 或保存状态。无隐藏思维链、原始工具结果或任意 HTML 渲染。

### 实际 REST 契约

| 模式/动作                  | 接口                                                       | 请求/响应                                                                                                              |
| -------------------------- | ---------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------- |
| Retrieval-First / RAG 提交 | `POST /api/v1/conversations/{id}/messages`                 | 仅 `{ content }`；返回 `{ userMessage, assistantMessage }`；沿用 60 秒客户端超时                                       |
| ReAct / PER 提交           | `POST /api/v1/agent/executions`                            | `{ knowledgeBaseId, conversationId, agentMode, question }`，请求头 `Idempotency-Key`；新建 HTTP 202，幂等复用 HTTP 200 |
| 手动刷新任务               | `GET /api/v1/agent/executions/{id}?knowledgeBaseId={kbId}` | 完整 `ExecutionResponse`；Query key 同时包含知识库 ID 和执行 ID                                                        |

相对开发文档第 7.5 节示例：实际 executionId 是 UUID；创建响应包含完整 ExecutionResponse，并可能已处于 RUNNING 或终态，不能硬编码 PENDING；详情 GET 必须带 `knowledgeBaseId`。后端状态白名单为 PENDING、RUNNING、PAUSED、SUCCEEDED、FAILED、CANCELLED。采用实际 `ExecutionController`、`CreateExecutionRequest`、`ExecutionResponse`、`ExecutionApplicationService` / `ExecutionStore` / `ExecutionRunner` 和 `AgentResult` 代码核对，客户端拒绝知识库、会话、模式或执行 ID 不匹配的响应。

FE07 交付时使用手动 REST 刷新；连接、订阅、重连、事件协调和自动刷新现已由 FE08 完成，见下文。FE09 的 Agent panel/步骤/工具可视化，以及 FE10 的恢复/取消/任务中心仍属于后续模块。

### 后端缺口及实际联调限制

当前 `ExecutionRunner` 读取会话上下文，`ExecutionStore.complete` 仅完成 Execution 状态，不调用 Conversation 的轮次持久化服务。因此 Execution 的用户问题和最终回答不会出现在 Conversation 消息历史，连续追问也不会把这些执行轮次作为新的服务端会话上下文。后端消息 DTO 不含 executionId，也没有按 conversationId 查询 Execution 的接口。

本模块将关联保留在当前应用的 TanStack Query mutation/query 缓存中，并在界面提示完整刷新会丢失关联。没有把执行回答注入服务端消息缓存，也没有用 localStorage 冒充服务端持久化。要实现真正的刷新恢复及 Execution 多轮上下文，后端需补齐执行轮次持久化和可查询的消息—执行关联；本次未修改后端。

读取 `http://localhost:8080/v3/api-docs` 时连接被拒绝，未进行真实数据库、模型、检索或后台执行联调。Vitest 的 REST 使用 **MSW Mock**，Playwright 的 REST 使用测试进程中的 **REST Mock**。MSW 演示中新建 Execution 为 PENDING，首次查询（FE08 自动核对或手动刷新）返回 Mock 完成答案，且不会写入 Conversation 历史，以保持与当前后端行为一致。浏览器完整刷新还会重置全部 MSW 内存数据。

### FE07 文件清单与依赖

以下仅列本模块新增或修改的文件，路径相对 frontend；保留已有 FE06 和后端未提交改动。

新增：

- `src/api/agent-gateway.ts`、`src/api/agent-gateway.test.ts`
- `src/api/execution.api.ts`、`src/api/execution.api.test.ts`
- `src/features/chat/agent.hooks.ts`、`src/features/chat/AgentExecutionMessage.tsx`
- `src/features/chat/execution-message.ts`、`src/features/chat/execution-message.test.ts`
- `src/test/mocks/execution-fixtures.ts`、`src/test/mocks/execution-handlers.ts`

修改：

- `src/api/contracts.ts`、`src/api/query-keys.ts`
- `src/features/chat/conversation.hooks.ts`、`src/features/chat/ChatPage.tsx`、`src/features/chat/ChatThread.tsx`、`src/features/chat/ChatMessage.tsx`、`src/features/chat/ChatPage.test.tsx`
- `src/test/mocks/fixtures.ts`、`src/test/mocks/handlers.ts`
- `e2e/chat.spec.ts`、`README.md`

无依赖新增/升级，未修改 package.json、package-lock.json 或前端环境变量。

### 人工验收 FE07

1. 真实联调：启动实际后端和其依赖，保持 `VITE_USE_MSW=false`，执行 `npm run dev`；演示可单独设置 `VITE_USE_MSW=true`，演示数据全部是 Mock。
2. 从知识库进入聊天，创建 Retrieval-First 会话并发送问题。Network 中应只有消息提交 POST，回答带真实消息 ID，刷新页面后仍从后端历史恢复。已有 RAG 会话行为相同。
3. 分别创建 ReAct、Plan-Execute-Reflect 会话并提问。Network 中应为 Execution POST，携带上述四个 DTO 字段及幂等键，不再发消息提交 POST。提交前出现 pending assistant，提交后相同客户端消息 ID 关联真实 executionId；界面显示“任务已提交”。
4. PENDING / RUNNING 时输入及重复发送、删除被阻止。切到其他会话，确认问题没有混入其他会话；切回确认执行 ID、关联和等待状态保留。
5. 点击“刷新执行状态”，确认 GET 带正确 knowledgeBaseId；完成后展示真实最终回答及 Citation Drawer，输入恢复可用。暂停、失败、取消、成功但无答案时核对相应提示。不会凭空展示工具步骤。
6. 模拟创建请求失败，确认草稿恢复且不自动重试；同一问题手动重发复用幂等键，修改问题后使用新键。模拟详情 GET 失败，确认仍保留原任务并仅通过 GET 重试。
7. 输入含 HTML 的问题/回答，核对纯文本或安全 Markdown 展示；异常字段和隐藏推理不得出现。完成任务后删除会话，确认关联及详情缓存一起清理。
8. 核对界面中的历史限制提示：当前后端尚未保存 Execution 轮次，完整刷新无法恢复这些轮次；该项需后端补齐后才能验收通过。

### FE07 实际验证记录（2026-10-11）

| 实际命令                                                                   | 结果                                                                                          |
| -------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------- |
| `npm run typecheck`                                                        | 通过；最终 `npm run build` 再次执行同样的 typecheck，包含全部最终源码                         |
| `npm run lint`                                                             | 最终通过，0 warning                                                                           |
| `npm run test -- --maxWorkers=1 --no-file-parallelism --testTimeout=60000` | 最终 11 个文件、56 个用例全部通过，包含 FE06 回归及连续 Execution 顺序/关联回归；没有跳过用例 |
| `npm run test:e2e -- --project=chat --reporter=line`                       | 4 项全部通过，包含 FE06 桌面/小屏及 FE07 ReAct/PER 模式；REST 全部为测试进程中的 Mock         |
| `npm run build`                                                            | 最终通过，3584 个模块；JS 1670.17 kB、gzip 523.82 kB；存在大于 500 kB 的 bundle 警告          |
| `npx prettier --check`（以上 FE07 21 个新增/修改文件作为显式路径参数）     | 通过；这些文件也执行了 `npx prettier --write`                                                 |
| `git diff --check -- frontend`                                             | 通过                                                                                          |

初始沙箱 `npm run test` 在加载阶段失败（Vitest spy.js 的 realpath EPERM，未执行用例）；`npm run test -- --pool=threads --maxWorkers=1 --no-file-parallelism` 仍遭遇相同错误。沙箱 `npm run build` 在 Vite 解析 main.tsx 时遭遇 realpath EPERM。改用正常用户环境后，初次 `npm run test` 55/55 通过、构建通过；诊断用单项 `npm run test -- src/features/chat/ChatPage.test.tsx --reporter=verbose --testNamePattern='binds REACT'` 为 1 通过、19 未选中，此结果不作为全量通过依据。

之后同时运行浏览器验收和全量 Vitest 时，已有 QuickQueryPanel 用例超过默认 5 秒时限（54 通过、1 超时）；未修改或跳过该用例。使用上表的单进程命令后 55/55 通过；补充连续执行的显示顺序回归并修正顺序后，最终 56/56 通过。最终 lint、格式检查和构建也已重新执行。生产构建保留 bundle 大小警告，未在 FE07 做无关的分包重构。

## FE08：WebSocket 实时进度

`ProgressWebSocketClient` 使用浏览器原生 WebSocket，复用连接并按知识库与资源维护订阅。`useExecutionProgress`、`useDocumentProgress` 已接入聊天执行回复、文档详情及列表中的处理进度；普通事件更新安全摘要，缺口、订阅确认、重连和终态通知会立即通过 TanStack Query 重读 REST。处理中的资源保留每 5 秒的状态核对，覆盖订阅拒绝、网络断开、终态事件先于事务可见和服务端未推送事件的情况。

实际 M18 协议使用 `resource`、`knowledgeBaseId`、`executionId` / `documentId`；文档 ID 位于事件的 `payload.documentId`，事件 `executionId` 是摄取任务 UUID。序列按任务隔离，重复/过期事件不会回退进度；终态以 REST 确认为准，确认后释放订阅。

真实开发联调时保持 `VITE_WS_BASE_URL=/ws`，为 **启动 Vite 的进程环境**配置与后端一致的 `PROGRESS_SUBSCRIPTION_TOKEN`。Vite 代理在服务端设置握手 `Authorization`；不把令牌写入前端环境文件、`VITE_*` 配置、URL 或浏览器代码。浏览器原生 WebSocket 无法自定义该 header；未配置代理鉴权时默认后端会拒绝订阅，前端继续 REST 轮询。生产环境应通过有用户权限校验的可信网关或后端用户鉴权接入。

无新增或升级依赖。协议差异、文件清单、验证记录和人工验收步骤见 [FE08 交付说明](../docs/fe08-progress-websocket.md)。FE08 的浏览器场景可单独运行：

```powershell
npm run test:e2e -- --project=chat e2e/progress.spec.ts --workers=1 --reporter=line
```

这些自动化使用 REST/WebSocket Mock；当前后端的 Execution 轮次持久化和刷新恢复限制仍以 FE07 的说明为准。

## FE09：Agent Execution 可视化

异步 Chat 的运行详情默认收起；点击“运行详情”或每轮回复的“查看执行步骤”后，宽度至少 1200px 时显示右侧面板，窄屏打开 Drawer。同一会话的执行默认展示最新一轮，也可切换已有执行。面板与聊天回复共享 TanStack Query 缓存和 FE08 的 WebSocket 订阅。

面板展示真实 `steps` 的 Plan / Step Timeline、当前步骤、依赖、完成/失败/跳过状态，以及 `toolCalls` 的工具名、状态、耗时和后端允许的数字参数摘要。PLAN / REFLECTION 的展示使用后端操作标识；不生成缺失的计划目标、步骤描述、证据数量或隐藏推理。最终引用复用 Citation Drawer。

运行中的小圆点采用低频透明度变化；`prefers-reduced-motion` 下关闭动画，终态/暂停后停止。实时连接不可用、重连、事件缺口、详情读取失败与 `retryable` 状态分别提示。详情读取的重试只发 GET；恢复/取消操作属于 FE10，本模块没有接入这些动作。

Retrieval-First / RAG 仍通过同步消息接口返回回答和引用，没有可用执行步骤，默认不展示运行面板。实际 DTO 没有模型/检索 `degraded` 字段，当前降级提示表示实时传输降级。Execution 与会话历史的持久关联仍依赖后端补齐，完整页面刷新后的恢复限制沿用 FE07/FE08。

文件清单、实际接口、验证结果与人工验收见 [FE09 交付说明](../docs/fe09-agent-visualization.md)。新增测试全部使用 REST/WS Mock，没有进行真实模型与数据库的端到端联调；未新增依赖。

## FE10–FE13：任务恢复、文档预览、Dashboard 与质量

任务中心支持筛选/分页和手动打开已知任务；独立详情通过 UUID + knowledgeBaseId 恢复 REST 状态，提供按服务端状态机控制的 Resume/Cancel，并处理并发恢复 409。聊天执行回复可直接打开该稳定地址。

文档详情增加原文件下载、浏览器 PDF 预览/页码定位与分页抽取文本，引用可跳转文档；DOCX 采用抽取文本和下载。Dashboard 的两个独立滚动列表使用 `GET /api/v1/dashboard/knowledge-bases?page&size` 与 `GET /api/v1/dashboard/documents?page&size`，首次各取 5 条，滚动到底部才加载下一页；后端按 `updatedAt DESC, id DESC` 排序并仅查询摘要字段。标题栏固定，长名称和描述可断行，请求失败提供重试且保留已加载内容；V14、V15 分别为文档和知识库添加排序索引。旧 `/documents/recent` 接口保留兼容。任务记录与 System 诊断经高级工具进入。任务列表分页/筛选与系统健康汇总接口已接通，文档下载与分页片段读取已接通，相邻片段 Context 接口仍未提供；Context 响应 DTO 未定义，生产环境展示本次回答的 quote，只有 Mock 环境可按需展开相邻上下文。

无新增或升级依赖。单元/组件测试和 Mock 浏览器九步工作流、409/Cancel/刷新恢复已建立；真实后端的独立验收命令为 `npm run test:e2e:live`，要求后端及摄取/检索/模型依赖就绪。Resume 用例额外读取非机密的 `PLAYWRIGHT_RESUME_EXECUTION_ID` / `PLAYWRIGHT_RESUME_KB_ID`。自动化 Mock 的 Citation 不代表真实模型 Citation。

文件清单、接口差异、实际验证结果、未完成项和人工验收见 [FE10–FE13 交付记录](../docs/fe10-fe13-delivery.md)。

### 任务记录与系统诊断（2026-10-20）

- `GET /api/v1/agent/executions?page=0&size=20&status=SUCCEEDED&agentMode=REACT`：页码从 0 开始，默认 20 条、最大 100 条；状态、模式可独立或组合筛选。按 `updatedAt DESC, executionId DESC` 稳定排序，只返回摘要字段。V16 添加全局、状态、模式的排序索引。
- `GET /api/v1/system/health`：检查 MySQL、Redis、RabbitMQ、Qdrant、Elasticsearch，响应只包含整体状态与固定组件的 `UP` / `DOWN` / `UNKNOWN`。五项并行，整体最多等待 3 秒，结果缓存 5 秒；依赖不可用仍返回可展示的诊断结果，刷新失败保留上次结果。
- 公网访问继续使用现有登录会话。诊断响应不透传 Actuator 详情、异常、主机地址或凭据，也不调用模型。

### 文档正文与原文件接口（2026-10-20）

`GET /api/v1/knowledge-bases/{kbId}/documents/{documentId}/chunks?page=0&size=50` 返回按 `chunkIndex` 升序的持久化片段，默认每页 50 条、最大 100 条，包含 `content/page/size/totalElements/totalPages`；单个片段仅包含 `chunkId/chunkIndex/content/pageFrom/pageTo/sectionTitle`。复用既有文档顺序索引，不重新解析或建立索引。

`GET /api/v1/knowledge-bases/{kbId}/documents/{documentId}/download` 按原始字节流下载 TXT、PDF、DOCX。提供原始文件名和附件类型，不改变 TXT 的原编码。两项接口均先验证知识库与文档归属；错误返回明确的 JSON 状态码，即使客户端只接受二进制响应。
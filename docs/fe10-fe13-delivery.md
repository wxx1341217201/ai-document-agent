# FE10–FE13 前端交付记录

日期：2026-10-11。范围：`frontend` 的 React + TypeScript 前端，沿用已有 FE06–FE09 实现；未修改后端、未开发 FE14。工作区原有后端、benchmark 和其他前端改动均保留。

2026-10-19 追加更新：FE12 工作台已接入后端新增的分页摘要接口，支持独立滚轮加载，验证和部署记录见 `docs/hybrid-deployment.md`。

2026-10-20 追加更新：任务列表后端分页、模式/状态筛选、系统健康汇总、文档片段分页和原文件下载已实现，旧的缺失接口说明仅代表最初交付时的状态。

## 完成内容

- FE10：任务列表、状态/模式筛选、分页、手动打开已知任务、独立任务详情及稳定 URL；详情通过 scoped REST 恢复，并复用 WebSocket/轮询。Resume、Cancel 按实际服务端状态机控制，取消需确认，同一页面重复点击只发送一次请求。409 后重新读取服务端状态，动作不会自动重试；过期恢复由服务端判断。
- FE11：下载原文件、浏览器原生 PDF 预览与 `#page` 页码定位、分页 Chunk/抽取文本、引用 Drawer 的原文与相邻上下文演示、引用跳转文档及页码。DOCX 展示抽取文本和下载。文档文本和文件名只作为文本处理；PDF 验证文件签名并固定 MIME，避免把 HTML 错误响应嵌入页面。
- FE12：知识库、文档使用两个独立的定高滚动列表，分别请求 `GET /api/v1/dashboard/knowledge-bases?page&size` 和 `GET /api/v1/dashboard/documents?page&size`，首次各取 5 条，滚轮到底再加载下一页。后端按 `updated_at DESC, id DESC` 稳定排序，仅选择列表所需字段，V14/V15 提供对应排序索引。长名称及描述可换行，标题栏固定，下一页失败保留当前内容并提供重试。Execution 仍使用原分页 API 汇总，System Health 保持独立页面/卡片。
- FE13：补齐 formatter、错误归一化/脱敏、状态映射、纯 WebSocket reducer 测试，保留并回归已有 AgentGateway 测试；覆盖知识库表单、并发上传队列、ChatMessage、CitationDrawer、Agent Timeline、任务恢复/动作、预览和 Dashboard。新增完整工作流及并发恢复浏览器回归，并提供独立真实后端验收配置。
- 上传队列修复：每个上传项使用独立 `mutateAsync` 生命周期，避免同一个 mutation observer 的逐次回调被后续上传覆盖，导致部分队列项永远显示上传中。

所有 REST 请求仍集中在 `src/api`，服务端状态使用 TanStack Query。Agent 仅展示结构化步骤、工具摘要、状态、答案与引用；没有显示隐藏思维链或执行任意 HTML。

## 实际接口与后端阻塞

已检查开发文档与当前 Java Controller/DTO/状态机代码。尝试读取 `http://localhost:8080/v3/api-docs` 时连接被拒绝，当前没有可用真实后端供联调。

下表的 `/api/v1` 是统一前缀：

| REST 接口                                                                                      | 本轮用途                  | 当前后端状态                                        |
| ---------------------------------------------------------------------------------------------- | ------------------------- | --------------------------------------------------- |
| `GET /dashboard/knowledge-bases?page&size`                                                     | 工作台知识库滚动分页      | 2026-10-19 已新增                                    |
| `GET /dashboard/documents?page&size`                                                           | 工作台文档滚动分页        | 2026-10-19 已新增                                    |
| `GET /knowledge-bases?page&size`                                                               | 知识库列表                | 已存在                                              |
| `GET /knowledge-bases/{kbId}/documents?page&size`                                              | 知识库内文档、上传后状态  | 已存在                                              |
| `GET /knowledge-bases/{kbId}/documents/{documentId}`                                           | 文档详情/READY 状态       | 已存在                                              |
| `POST /knowledge-bases/{kbId}/documents`                                                       | multipart 上传            | 已存在，沿用                                        |
| `GET /agent/executions?page&size&status&agentMode`                                             | 任务列表、最近 Execution  | 2026-10-20 已补齐分页与组合筛选            |
| `GET /agent/executions/{executionId}?knowledgeBaseId={kbId}`                                   | 刷新后的任务详情/恢复状态 | 已存在                                              |
| `POST /agent/executions/{executionId}/resume?knowledgeBaseId={kbId}`                           | Resume                    | 已存在，202；并发/状态冲突 409                      |
| `POST /agent/executions/{executionId}/cancel?knowledgeBaseId={kbId}`                           | Cancel                    | 已存在，200；运行时可先返回 `cancelRequested`       |
| `GET /knowledge-bases/{kbId}/documents/{documentId}/download`                                  | 下载/PDF 二进制           | 2026-10-20 已接通原始字节流下载            |
| `GET /knowledge-bases/{kbId}/documents/{documentId}/chunks?page&size`                          | 分页文本                  | 2026-10-20 已接通正文分页查询            |
| `GET /knowledge-bases/{kbId}/documents/{documentId}/chunks/{chunkId}/context?before=1&after=1` | Citation 相邻上下文       | §8.4 只给 URL，未定义响应 DTO；当前 Controller 缺失 |
| `GET /system/health`                                                                           | 安全 System Health        | 2026-10-20 已接入五个真实依赖检查            |

沿用会话 CRUD/消息、`POST /agent/executions` 和 `Idempotency-Key`；本轮未更改它们的后端契约。实时进度沿用 `/ws/progress`、`SUBSCRIBE/UNSUBSCRIBE`，按 `resource`、`knowledgeBaseId` 和 execution/document ID 订阅；缺口、重连与终态都通过 REST 核对。

契约差异与明确限制：

1. Execution 详情使用实际 `createdAt`、`toolCalls`、`retryable`、`cancelRequested`、`deadlineAt` 与 `version`。列表使用开发文档规划的 `ExecutionSummary.startedAt`，未把详情 DTO 的字段擅自改名。
2. Resume 只允许 PAUSED 或可重试 FAILED，且没有取消请求；Cancel 只允许 PENDING/RUNNING/PAUSED。服务端还会校验租约与截止时间。取消 RUNNING 后以 REST 返回的最终状态为准。
3. Citation Context 的响应保持 `unknown`。只有测试、E2E 或明确启用 MSW 的环境使用“返回 §8.3 Chunk 数组”的 **Mock 约定**，界面明确标注 Mock；生产/真实联调展示原始 quote 和等待契约提示，没有编造生产响应字段。后端确认 DTO 后仍需接入该解析器。
4. Health 只投影允许的组件名和 UP/DOWN/UNKNOWN；不回退显示原始 Actuator details、密码、连接字符串、内部路径或堆栈。
5. 现有 KB/文档列表按 ID 升序，不支持按更新时间排序。Dashboard 读取所有页再取最近 5 条，避免把首页误当最近数据；数据量增大后需要后端排序或聚合 API。
6. 任务详情 URL 包含 UUID 与知识库 ID，可刷新重读运行/暂停任务；列表 API 缺失时仍可从聊天回复的“打开任务详情”或手动输入 ID 进入。当前后端尚未把 Execution 轮次及其关联持久化到会话消息，整个聊天页刷新后的执行历史恢复仍被后端阻塞。
7. 浏览器 PDF 阅读器不支持嵌入/页码跳转时使用下载。Chunk ID 不代表连续索引，不推算不存在的相邻片段；文档页仅突出当前分页中实际返回的引用片段。

## Mock 与真实验收边界

- Vitest 使用 MSW 或明确的 API 服务 Mock。上传队列组件测试使用服务 Mock；浏览器工作流覆盖真实 multipart 请求与上传的 TXT/PDF 字节，但摄取、READY、模型回答、引用与恢复状态仍是 Playwright REST/WS Mock。
- `e2e/workflow.spec.ts` 在 Node 测试进程维护 Mock 状态，浏览器刷新后仍能重读该状态，覆盖九步工作流、实时事件、FAILED→Resume、RUNNING 刷新、终态刷新、409 与 Cancel。
- 这些测试没有证明真实模型产生 Citation。`e2e/live-workflow.spec.ts` 和 `playwright.live.config.ts` 不拦截 REST/WS、不启用 MSW；要求真实摄取、引用来自上传文档且有真实 chunkId，并提供预置可恢复任务的独立 Resume 用例。
- 本轮只执行 live suite 的 `--list`，没有运行真实后端验收。缺少运行中的后端、摄取/检索/模型依赖及可恢复任务，因此“真实 Citation/真实失败恢复”仍待联调。
- 开发模式 MSW 的种子任务在浏览器刷新时重建，不能把它当成任务持久化证据。

## 文件清单

以下路径相对于 `frontend`。已有 FE06–FE09 文件只是本轮增量接入，不表示整份文件由本轮新建。

新增：

- API：`src/api/dashboard.api.ts`、`document-preview.api.ts`、`system.api.ts`、`progress-reducer.ts`；相应 `*.test.ts`；`client.test.ts`（错误归一化专项）。
- FE10：`src/features/execution/ExecutionListPage.tsx`、`ExecutionDetailPage.tsx`、`execution.hooks.ts`、`execution-state.ts`、`ExecutionDetailPage.test.tsx`、`execution-state.test.ts`。
- FE11：`src/features/document/DocumentDownloadButton.tsx`、`DocumentPreview.tsx`、`preview.hooks.ts`、`DocumentPreview.test.tsx`；`src/features/knowledge-base/CitationDrawer.test.tsx`。
- FE12：`src/features/dashboard/dashboard.hooks.ts`、`DashboardPage.test.tsx`；`src/features/system/SystemHealthCard.tsx`、`SystemPage.tsx`。
- 质量：`src/utils/status.ts`、`status.test.ts`、`format.test.ts`；`src/features/knowledge-base/KnowledgeBaseFormModal.test.tsx`、`src/features/document/DocumentUploadDragger.test.tsx`、`src/features/chat/ChatMessage.test.tsx`。
- Mock：`src/test/mocks/document-preview-fixtures.ts`、`document-preview-handlers.ts`。
- E2E：`e2e/workflow.spec.ts`、`e2e/helpers/workflow-mock.ts`、`e2e/live-workflow.spec.ts`、`e2e/fixtures/idempotency.txt`、`playwright.live.config.ts`。

修改/增量扩展：

- API：`src/api/contracts.ts`、`client.ts`、`query-keys.ts`、`document.api.ts`、`knowledge-base.api.ts`、`execution.api.ts`、`execution.api.test.ts`、`progress-websocket.ts`。
- 路由/布局：`src/app/router.tsx`、`src/components/layout/AppLayout.tsx`、`src/components/feedback/index.tsx`、`src/styles/index.css`。
- 页面/组件：`src/features/dashboard/DashboardPage.tsx`；`src/features/document/DocumentDetailPage.tsx`、`DocumentUploadDragger.tsx`、`document.hooks.ts`、`document-utils.ts`；`src/features/knowledge-base/CitationDrawer.tsx`；`src/features/chat/AgentPanel.tsx`、`AgentExecutionMessage.tsx`、`ChatMessage.tsx`；`src/utils/format.ts`。
- Mock：`src/test/mocks/fixtures.ts`、`handlers.ts`、`execution-fixtures.ts`、`execution-handlers.ts`。
- 测试/配置：`e2e/agent-panel.spec.ts`（给已有引用回归补充 Context Mock）、`playwright.config.ts`、`tsconfig.node.json`、`package.json`、`.gitignore`。
- 说明：`frontend/README.md`、`docs/development-progress.md`、本文件。

## 依赖变化

没有新增或升级依赖，`package-lock.json` 未修改。复用现有 TanStack Query、Ant Design、Axios、MSW、Vitest 和 Playwright；PDF 采用浏览器原生阅读器。新增 npm 脚本 `test:e2e:live`，把 E2E 文件加入 TypeScript 检查范围，并忽略浏览器验收产物目录 `output/playwright/`。没有新增含 Secret 的前端环境变量。

## 实际验证

最终成功命令如下；中间失败与修复不作为通过结果。

| 命令                                                                                                                                                      | 结果                                              |
| --------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------- |
| `npm run typecheck`                                                                                                                                       | 通过，包含 E2E/Playwright 配置                    |
| `npm run lint`                                                                                                                                            | 通过，0 warning                                   |
| `npm run test -- --maxWorkers=1 --no-file-parallelism --testTimeout=60000 --reporter=dot --reporter=json --outputFile=test-results/fe10-fe13-vitest.json` | 通过，30 个文件、156 项；230.23s                  |
| `npm run test:e2e -- --workers=1 --reporter=line`                                                                                                         | 通过，12/12，约 1 分钟；REST/WS Mock              |
| `npm run test:e2e:live -- --list`                                                                                                                         | 通过，发现 2 项；未执行真实验收                   |
| `npm run build`                                                                                                                                           | 通过；3608 个模块，JS 1731.02 kB / gzip 540.23 kB |
| `npx prettier --check`（本轮显式文件路径）                                                                                                                | 源码通过；交付 Markdown 格式修正后单独检查通过    |
| `git diff --check -- frontend docs/fe10-fe13-delivery.md docs/development-progress.md`                                                                    | 通过；只有 Windows LF/CRLF 提示                   |

构建保留大于 500 kB 的 bundle 警告，未在本轮做无关的分包重构。Vitest 仍有原有 Ant Design 的 React 19/static message 与旧 `destroyOnClose` 提示；全部用例通过。已有浏览器场景测试 WS 不可用时的轮询，因此没有真实后端时 Vite 会记录 WS proxy `ECONNREFUSED`，不作为真实 WS 成功证据。

最终桌面 Dashboard 与 390px 任务详情截图已查看，窄屏浏览器断言确认页面没有横向溢出。截图位于 `frontend/output/playwright/fe12-dashboard-desktop.png` 和 `fe10-execution-mobile.png`（Mock 数据，产物被 Git 忽略）。

中间发现并修复：组件测试的重复 Mock 短 UUID 选择器、上传队列 observer 回调覆盖、浏览器新会话尚未完成导航就发送问题的竞态，以及已有 FE09 引用测试未 Mock 新 Context 请求。错误脱敏回归曾失败，已限制只有 400 校验响应可解析字段错误，脱敏后重跑全量测试。沙箱 Vitest 加载因 Windows realpath EPERM 失败，正常用户环境用于执行测试；这些问题均未通过跳过用例掩盖。

## 人工验收

1. 在 `frontend` 运行 `npm run dev`；真实联调设置 `VITE_USE_MSW=false` 并启动实际后端及其摄取/检索/模型依赖。WebSocket 握手令牌只放在启动 Vite 的服务端进程环境 `PROGRESS_SUBSCRIPTION_TOKEN`，不要写入 `VITE_*` 或浏览器 URL。
2. 创建 KB，上传 `e2e/fixtures/idempotency.txt` 和测试 PDF；核对文件名、队列各项结果、READY/失败状态。未完成的上传不应冒充 READY。
3. 创建 Retrieval-First 会话并询问资料相关问题，打开 Citation；核对文档、chunkId、quote。真实环境 Context 会显示契约待确认；新接口补齐后复核相邻原文。通过“查看文档”进入 PDF 指定页，验证抽取文本分页与下载；DOCX 核对文本及下载。
4. 创建 ReAct/PER 任务，查看结构化步骤与工具摘要，打开独立任务详情。保存地址并刷新，核对服务端状态和步骤仍可读取。
5. 预置 PAUSED 或 retryable FAILED 任务，点击 Resume，核对 202 与随后 GET 状态；双击或另开标签页恢复同一任务，核对 409 提示、刷新权威状态且没有自动重复 POST。对 PENDING/RUNNING/PAUSED 执行取消，核对确认框与最终 CANCELLED；终态按钮禁用。
6. 打开任务中心；列表接口未实现时核对错误提示，并用已知 UUID/KB ID 进入任务。打开 Dashboard 和 System，核对最近数据、各卡片独立失败、空态和安全组件状态；不要把缺失接口显示成 UP。
7. 在 390px 宽度核对操作、Drawer、列表滚动和文档预览无整页横向溢出。输入含 HTML 的文档内容/文件名与答案，确认只作为文本/安全 Markdown 展示。
8. 自动化 Mock 验收运行 `npm run test:e2e -- --workers=1`。真实后端就绪后运行 `npm run test:e2e:live`；Resume 用例还需设置非机密的 `PLAYWRIGHT_RESUME_EXECUTION_ID` 与 `PLAYWRIGHT_RESUME_KB_ID` 指向可恢复任务。

Mock 手动演示可设置 `VITE_USE_MSW=true`，知识库 ID 为 `1`，暂停种子任务 UUID 为 `11111111-1111-4111-8111-111111111111`。该演示数据会在页面刷新时重置；持续恢复验收应使用真实后端或 Playwright 工作流 Mock 的测试进程。

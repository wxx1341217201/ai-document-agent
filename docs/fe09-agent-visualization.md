# FE09：Agent Execution 可视化交付说明

依据《多范式 AI-Agent 文档智能处理平台—前端 UI 与接口完整开发文档》的 FE09 / 第 5.7 节，以及当前 Execution Controller/DTO、ExecutionStore、ReActAgent、PlanExecuteReflectAgent、Executor 和 M18 Progress 源码实现。开发前已检查 frontend 源码、package.json、Git 状态与 diff，保留工作区已有 FE06–FE08、后端及 benchmark 改动。

## 完成内容

- Chat 桌面右侧 Agent 面板；1200px 以下改用右侧 Drawer，入口为“Agent 面板”和每轮回复的“查看执行步骤”。同一会话可切换已有执行，新提交默认选择最新一轮。
- 真实 Plan / Step Timeline：保留 REST 步骤顺序，显示 stepId、kind、operation、status、dependsOn、安全 errorCode 和当前步骤高亮。支持 PENDING / RUNNING / COMPLETED / SUCCEEDED / FAILED / SKIPPED，以及持久执行包装步骤的 AGENT kind。
- Tool invocation summary：工具名、状态、耗时、数字参数摘要与安全错误码。未知耗时显示“耗时未知”，不当作 0；不输出工具原始参数、工具输出或任意 JsonNode 内容。
- 低频 running 圆点动画；暂停和终态停止，遵守 `prefers-reduced-motion: reduce`。
- 实时连接降级、重连重试、事件缺口、REST 读取错误与失败任务 `retryable` 提示。读取错误保留最近一次成功快照，可手动重试 GET；不会自动重新提交任务。
- PLAN_CREATED / STEP_STARTED / STEP_COMPLETED / TOOL_COMPLETED 到达时，通过 TanStack Query 重新读取 Execution 详情。事件缺少完整步骤和工具参数，不据此构造条目。复用 FE08 订阅、重连、终态刷新和每 5 秒 REST 核对；多个观察者共享订阅。
- 成功后显示经过已有 final-answer projection 校验的引用，点击复用 Citation Drawer。窄屏引用点击会关闭 Agent Drawer，避免两个抽屉叠加。
- loading / empty / error / success 状态；同步模式只展示服务实际提供的完成状态与引用。

## 安全与实际契约差异

实际 REST 使用 `steps` 和 `toolCalls`，没有采用开发文档示例的 `toolInvocations`。GET 必须带 `knowledgeBaseId` 查询参数，执行 ID 为 UUID；没有新增列表、steps/tools 分拆接口或 FE10 恢复动作。

步骤只投影合法机器标识、已知种类/状态和依赖 ID；不读取 thought、reasoning、summary、内部状态等任意附加字段。工具参数只允许 ExecutionStore.safeTool 实际保存的 `queryLength` / `topK` / `chunkId` / `radius` / `documentId`，且值必须是 1–20 位数字字符串。重复 stepId / invocationKey 过滤，非法条目忽略，缺少数组时显示空状态。文件名、用户问题及引用均作为 React 文本渲染；现有回答继续使用安全 Markdown 组件，不执行任意 HTML。

当前后端仅暴露 `validated_plan`、SEARCH / FETCH_CONTEXT / SYNTHESIZE、`coverage_complete` / `coverage_gap` 等机器操作标识。面板将已知操作映射为中文标题；未添加计划目标、模型反思原文、missingEvidence 数量或 revised 字段。本轮问题来自实际提交内容，不能等同于服务端生成的完整 Plan。

`degraded` 没有出现在当前 ExecutionResponse / AgentResult 中。“实时进度已降级”表示 WebSocket 不可用、改由 REST 核对；不代表模型或检索质量降级。`retryable` 是服务端返回的布尔值，ExecutionStatus 没有 RETRYING；界面不虚构重试次数或重试中的任务状态。恢复/取消入口仍留在 FE10 范围。

没有新增前端环境变量或 Secret；未修改后端代码。

## REST / WebSocket

| 用途                         | 实际接口                                                                                                           |
| ---------------------------- | ------------------------------------------------------------------------------------------------------------------ |
| 创建 ReAct / PER 任务        | `POST /api/v1/agent/executions`，复用 AgentGateway 和 `Idempotency-Key`                                            |
| 面板详情、事件刷新与读取重试 | `GET /api/v1/agent/executions/{executionId}?knowledgeBaseId={kbId}`                                                |
| 同步模式与会话历史           | 复用 `POST /api/v1/conversations/{conversationId}/messages`、`GET /api/v1/conversations/{conversationId}/messages` |
| 会话列表                     | 复用 `GET /api/v1/knowledge-bases/{kbId}/conversations`                                                            |
| 执行进度                     | `/ws/progress`，复用 M18 `SUBSCRIBE` / `UNSUBSCRIBE`                                                               |

实际订阅帧使用 `resource: "EXECUTION"`、`knowledgeBaseId`、`executionId`，与文档示例中的 `resourceType` / `resourceId` 不同。服务端进度字段仍按 FE08 的白名单解析；没有增加新事件类型。连接鉴权及 Vite 服务端代理沿用 [FE08 说明](fe08-progress-websocket.md)，没有把令牌放入浏览器。

所有 REST 继续通过 `src/api`，执行实体和消息由 TanStack Query 管理；新增 local state 仅用于选择执行和 Drawer 开关。

## 文件与依赖

新增：

- `frontend/src/features/chat/AgentPanel.tsx`
- `frontend/src/features/chat/execution-visualization.ts`
- `frontend/src/features/chat/AgentPanel.test.tsx`
- `frontend/src/features/chat/execution-visualization.test.ts`
- `frontend/src/test/mocks/agent-panel-fixtures.ts`
- `frontend/e2e/agent-panel.spec.ts`
- `docs/fe09-agent-visualization.md`

修改：

- `frontend/src/api/contracts.ts`：提取实际 Step / Tool DTO 类型，补充实际步骤状态联合类型。
- `frontend/src/features/chat/ChatThread.tsx`：面板布局、执行选择、Drawer 和引用联动。
- `frontend/src/features/chat/AgentExecutionMessage.tsx`：查看当前执行步骤入口。
- `frontend/src/styles/index.css`：面板、Timeline、工具摘要与减少动画支持。
- `frontend/playwright.config.ts`：新增 FE09 测试归入已有无 Service Worker 的 chat 项目。
- `frontend/README.md`、`docs/development-progress.md`：交付与验收说明。

未新增/升级依赖，package.json / package-lock.json 未变更；无 FE10 或其他模块的大规模重构。

## 验证范围

新增 Vitest 14 项：结构化字段与数字摘要过滤、无隐藏推理/HTML、重复/非法数据、未知耗时、空/加载/成功状态、REST 失败保留快照与 scoped GET 重试、传输降级、retryable true/false、暂停/取消、选择已有执行、引用回调及同步模式限制。

新增 Playwright 两个 REST/WS Mock 场景：PER 桌面实时 Timeline 与依赖、GET 失败后的读取重试、终态引用、第二轮与历史执行选择、窄屏 Drawer/引用联动/页面无横向溢出、减少动画设置；ReAct COMPLETED、连接拒绝降级、FAILED / SKIPPED / 不可重试、未知耗时和隐藏字段过滤。没有用 Mock 结果证明真实后端或模型可用。

## 已执行验证（2026-10-11）

命令均在 frontend 执行：

| 命令                                                                                         | 实际结果                                                                              |
| -------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------- |
| `npm run typecheck`                                                                          | 通过；生产构建再次执行类型检查                                                        |
| `npm run lint`                                                                               | 通过，0 warning                                                                       |
| `npm run test -- --maxWorkers=1 --no-file-parallelism --testTimeout=60000 --reporter=dot`    | 15 个文件、101 项全部通过；包含新增 14 项及原有 87 项                                 |
| `npm run build`                                                                              | 通过，3591 个模块；JS 1699.18 kB / gzip 531.78 kB；保留原有超过 500 kB 的 bundle 提示 |
| `npm run test:e2e -- --workers=1 --reporter=line`                                            | 最终完整 10 项全部通过，包括 FE06–FE08 与新增 FE09 两个场景                           |
| `npm run test:e2e -- --project=chat --workers=1 --reporter=line agent-panel.spec.ts`         | 最终定向 2 项通过；稳定桌面/窄屏截图已人工检查                                        |
| `npx prettier --check`（本次显式文件清单）                                                   | 通过                                                                                  |
| `git diff --check -- frontend docs/fe09-agent-visualization.md docs/development-progress.md` | 通过                                                                                  |

首轮沙箱 Vitest 在加载本地依赖时出现 `realpath EPERM`，未执行测试；改用正常用户环境后新增 14 项及完整 101 项均通过。最终源码再次全量运行得到 15 个文件 / 101 项通过，无失败、无跳过。

首轮定向浏览器运行 1 通过、1 失败，失败点是 Ant Design 选择器输入被选中标签覆盖，已改为键盘打开。随后完整回归 9 通过、1 失败，定位到小屏入口的可访问名称包含图标名；补充显式 `aria-label` 后，最终完整回归 10 项全部通过。最后调整截图关闭有限过渡动画，定向两项再次通过，桌面和 390px 窄屏截图已检查。

已有 Mock 降级回归中 Vite 的后端 WS 代理会输出 `ECONNREFUSED`，不影响 REST Mock 验收；FE09 实时传输由 Playwright 显式 Mock。没有把沙箱启动失败、定向筛选通过或历史失败运行冒充最终全量通过。

## 后端限制与后续事项

- Retrieval-First / RAG 的同步消息 DTO 没有执行 ID、步骤或工具摘要。当前只展示完成状态和真实引用；逐阶段 Timeline 需要后端返回安全结构化信息。
- Execution 没有模型/检索 degraded、完整 Plan 目标和 reflection coverage/count 字段，无法展示不存在的数据。
- Execution 轮次没有持久化到 Conversation 历史，也没有会话到执行的查询接口；完整刷新后的任务恢复仍受 FE07/FE08 限制。面板选择和执行关联仅在当前应用会话中保留。
- 真实模型/数据库/鉴权 WebSocket 联调尚未进行。M18 的无历史回放和跨实例 fanout 限制沿用 FE08。
- 本模块不接入执行列表、Resume、Cancel；这些属于 FE10。

## 人工验收

1. 启动实际后端及依赖，配置 FE08 要求的服务端 WebSocket 代理鉴权；前端保持 `VITE_USE_MSW=false`，在 frontend 运行 `npm run dev`。使用至少 1200px 的窗口，从知识库打开或创建 ReAct / PER 会话。
2. 提交问题，确认 POST 带幂等键、GET 带知识库参数。桌面右栏显示对应 UUID 和本轮问题；步骤、依赖、工具耗时/安全摘要应与实际 GET 一致，不包含模型隐藏思维链。
3. PER 收到步骤/工具事件后应自动刷新 Timeline；检查当前步骤高亮和失败/跳过状态，最终回答和引用来自 REST。点击右栏引用，核对文档 ID、chunk ID 和页码。
4. DevTools 模拟连接不可用/拒绝，确认显示实时降级并继续 REST 核对；恢复连接后重新同步。模拟一次 GET 失败，确认保留快照，“重试加载详情”只重新读取，POST 次数不变。
5. 在 Mock 场景分别返回 retryable true/false、PAUSED、FAILED、CANCELLED；提示应与服务状态一致。停止状态不能继续 running 动画；启用减少动画时关闭圆点动画。
6. 完成第一轮后发送第二轮，通过选择器或“查看执行步骤”切换执行。切换会话再返回，关联仍由当前 Query session 保留；完整刷新受上述后端限制。
7. 将窗口缩到 390px，打开 Agent Drawer，检查步骤/工具/刷新操作；点击引用应关闭 Agent Drawer 并打开 Citation Drawer，不发生页面横向溢出。
8. Retrieval-First 会话应说明同步接口没有步骤，完成后可查看真实引用。通过 Mock 输入带 HTML 的问题/文件名与隐藏字段，确认作为文本显示或被过滤，不执行 HTML。

自动化 Mock 验收命令：`npm run test:e2e -- --project=chat --workers=1 --reporter=line agent-panel.spec.ts`。开发环境 MSW 演示仍沿用 FE07 简化执行响应；丰富步骤场景由新增测试显式 Mock，不会假冒生产数据。

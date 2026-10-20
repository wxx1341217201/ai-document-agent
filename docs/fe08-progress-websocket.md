# FE08：WebSocket 实时进度交付说明

实现以《多范式 AI-Agent 文档智能处理平台—前端 UI 与接口完整开发文档》的 FE08 / 第 11 节，以及当前 Java M18 源码为依据。交付范围为前端实时传输、REST 重同步、Hook、页面接入和验证。

## 实现与行为

- `ProgressWebSocketClient`：懒连接、共享连接、引用计数、`subscribe` / `unsubscribe`、显式 `reconnect`、`dispose`。最后一个订阅释放时关闭连接及定时器；仍有其他订阅时发送 `UNSUBSCRIBE`。
- 控制帧串行发送，避免无资源 ID 的 `SUBSCRIPTION_REJECTED` / `UNSUBSCRIBED` 被误分配。连接建立和控制帧确认均有 10 秒超时。
- 自动重连默认间隔为 1、2、4、8、15、30 秒，带 ±20% jitter，上限及间隔可以通过构造参数配置；收到有效 `SUBSCRIBED` 后才重置退避。
- 按流 UUID 保存序列及最多 256 个事件 ID。重复 ID、相同序列、较旧事件被忽略；较大的序列缺口立即触发 REST 查询。订阅确认的 `latestSequence` 建立基线，允许事件先于确认到达。文档更换摄取任务 UUID 后重新建立基线，过滤旧任务的延迟事件。
- 初次订阅成功及重连订阅成功均重读 REST，不依赖历史事件回放。终态通知触发资源及相关列表/历史缓存失效；若终态还未在 REST 中可见，继续订阅并每 5 秒查询。REST 确认 Execution 为 SUCCEEDED / FAILED / CANCELLED，或文档已结束处理后，取消订阅与轮询。
- `useExecutionProgress(kbId, executionId, options?)`、`useDocumentProgress(kbId, documentId, options?)` 返回 TanStack Query 查询结果及 `progress`。支持已有 REST `initialData`、禁用订阅及测试用客户端注入。资源切换、卸载、StrictMode 重挂载均清理旧订阅。
- 聊天的执行回复、文档详情和文档列表显示实时安全阶段摘要；最终状态、回答、引用和错误继续来自 REST。处理中的资源每 5 秒核对，覆盖网络错误、订阅拒绝、未收到推送和服务端多实例无 fanout 的情况。
- 事件 JSON 做运行时校验；只投影实际 `ProgressPayload` 的安全字段。不保留隐藏推理、任意工具参数或模型输出，不根据服务端传入的 `statusUrl` 发起任意 URL 请求。

## 实际接口与文档差异

连接：`/ws/progress`。默认通过 Vite 同源代理；HTTPS 页面使用 `wss:`。`VITE_WS_BASE_URL` 是基址（默认 `/ws`），客户端追加 `/progress`。

实际控制帧：

```json
{
  "action": "SUBSCRIBE",
  "resource": "EXECUTION",
  "knowledgeBaseId": 1,
  "executionId": "12345678-1234-1234-1234-123456789abc"
}
```

```json
{
  "action": "SUBSCRIBE",
  "resource": "DOCUMENT",
  "knowledgeBaseId": 1,
  "documentId": 101
}
```

退订沿用相同字段，`action` 改为 `UNSUBSCRIBE`。与开发文档第 11 节的 `resourceType` / `resourceId` 示例不同，当前 Handler 必须收到 `resource`、`knowledgeBaseId` 和具体资源字段。本模块仅实现实际 JSON 协议。

当前事件的 `executionId` 必填；文档事件的该值是摄取任务 UUID，`documentId` 位于 `payload`，不在事件顶层。服务端支持 `CONNECTED`、`SUBSCRIBED`（含资源、UUID、latestSequence）、`UNSUBSCRIBED`、`SUBSCRIPTION_REJECTED`。事件类型及安全 payload 以 `ProgressEventType` / `ProgressPayload` 为准。

REST 重同步复用已有 API 层：

| 资源               | 查询                                                                   |
| ------------------ | ---------------------------------------------------------------------- |
| Execution          | `GET /api/v1/agent/executions/{executionId}?knowledgeBaseId={kbId}`    |
| 文档               | `GET /api/v1/knowledge-bases/{kbId}/documents/{documentId}`            |
| 文档相关列表       | `GET /api/v1/knowledge-bases/{kbId}/documents?page={page}&size={size}` |
| Execution 相关会话 | 失效已有 Conversation / 消息查询；仍按已有 REST 契约读取               |

核对的后端源码：`ProgressWebSocketHandler`、`ProgressSubscriptionService`、`ProgressEvent`、`ProgressStream`、`ProgressPayload`、`ProgressEventType`、`ProgressHandshakeInterceptor`、Execution Controller/DTO。未修改后端。

## 开发代理鉴权

当前默认 M18 authorizer 要求握手 `Authorization: Bearer ...`，令牌由后端 `PROGRESS_SUBSCRIPTION_TOKEN` 配置；缺省会拒绝所有订阅。浏览器原生 WebSocket 不能设置自定义握手 header。

Vite 的 `/ws` 代理在服务端读取 **启动进程环境**中的同名键并设置 header。开发联调时为后端与 Vite 进程配置一致的令牌，保持 `VITE_WS_BASE_URL=/ws`。令牌不写入前端 `.env*` 文件，不使用 `VITE_` 前缀，不进入客户端代码、订阅帧或 URL。直接配置跨域后端 WS 地址会绕过该代理；默认共享令牌模式需经过服务端代理。

生产部署应使用有用户权限校验的可信网关或替换后端用户鉴权策略。默认共享令牌代表部署访问权限，不能代表单个用户对知识库的权限。鉴权不可用时界面继续读取 REST 权威状态。

## 文件清单与依赖

新增文件：

- `frontend/src/api/progress.contracts.ts`
- `frontend/src/api/progress-websocket.ts`
- `frontend/src/api/progress-websocket.test.ts`
- `frontend/src/features/progress/index.ts`
- `frontend/src/features/progress/useProgressSubscription.ts`
- `frontend/src/features/progress/useExecutionProgress.ts`
- `frontend/src/features/progress/useDocumentProgress.ts`
- `frontend/src/features/progress/progress.hooks.test.tsx`
- `frontend/src/test/mocks/progress-socket.ts`
- `frontend/e2e/progress.spec.ts`
- `docs/fe08-progress-websocket.md`

修改文件：

- `frontend/src/api/document.api.ts`（增加可取消的 GET）、`frontend/src/api/query-keys.ts`
- `frontend/src/features/chat/agent.hooks.ts`、`AgentExecutionMessage.tsx`、`ChatPage.test.tsx`
- `frontend/src/features/document/document.hooks.ts`、`DocumentDetailPage.tsx`、`DocumentTable.tsx`
- `frontend/src/test/mocks/server.ts`（为已有 REST 集成测试明确 Mock WS 拒绝）
- `frontend/vite.config.ts`、`frontend/playwright.config.ts`、`frontend/e2e/chat.spec.ts`
- `frontend/README.md`、`docs/development-progress.md`

未新增/升级依赖；package.json、package-lock.json 和前端公开环境变量模板没有变更。保留工作区已有 FE06/FE07、后端及 benchmark 改动。

## 验证范围

新增客户端测试覆盖共享订阅、控制帧次序、断开/错误、退避与 jitter、无回放的重连、重复/乱序/缺口、事件早于订阅确认、四种终态通知、新摄取流、订阅拒绝、超时、非法/跨资源事件、安全 payload、取消重连和重用。

新增 Hook 测试覆盖 REST 权威状态、相关缓存失效、重连 refetch、订阅拒绝后的轮询、终态事件先于 REST 可见、REST 失败后的恢复、取消旧 REST 及迟到结果、切换/卸载、非法/禁用/终态资源及 StrictMode。

浏览器新增三个 REST/WS Mock 场景：聊天的阶段更新、重复/乱序/缺口、断线重连和自动最终回答；文档详情与文档列表的实时阶段、终态 REST 刷新和退订。已有 FE07 回归的 Mock 由测试明确控制终态，允许正常后台轮询，继续验证关联、输入禁止、手动刷新与引用。

自动化均使用 Mock；没有进行真实数据库、模型和鉴权后端的端到端联调。当前 M18 无历史回放、跨实例 fanout；当前后端尚未把 Execution 轮次持久化到 Conversation，FE08 不补齐 FE09/FE10 或后端历史恢复接口。

## 实际验证记录（2026-10-11）

命令在 `frontend` 目录执行。最终结果：

| 命令                                                                                                                                                 | 结果                                                                                           |
| ---------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------- |
| `npm run typecheck`                                                                                                                                  | 通过；最终 `npm run build` 再次执行相同检查，包含全部最终源码                                  |
| `npm run lint`                                                                                                                                       | 最终通过，0 warning                                                                            |
| `npm run test -- --maxWorkers=1 --no-file-parallelism --testTimeout=60000 --reporter=dot --reporter=json --outputFile=test-results/fe08-vitest.json` | 最终 13 个文件、87 项全部通过，0 失败、0 跳过；包含新增客户端 22 项、Hook 9 项及原有 56 项回归 |
| `npm run test:e2e -- --workers=1 --reporter=line`                                                                                                    | 最终 8 项全部通过，包括 FE08 三个场景、FE06/FE07 和原有知识库/文档流程                         |
| `npm run build`                                                                                                                                      | 最终通过，3589 个模块；JS 1681.30 kB、gzip 527.15 kB；保留现有大于 500 kB 的 bundle 提示       |
| `npx prettier --check`（本次新增/修改文件显式清单）                                                                                                  | 通过                                                                                           |
| `git diff --check -- frontend docs/fe08-progress-websocket.md docs/development-progress.md`                                                          | 通过                                                                                           |

Vitest / Vite / Chromium 在初始沙箱中分别遇到 `realpath EPERM` 和浏览器缓存路径重定向；已在正常用户环境运行上表的完整测试及构建。没有用沙箱失败或定向筛选结果冒充全量通过。

回归期间修正了 FE07 Mock 对“仅手动 GET 才完成”的假设，改为由测试明确控制 REST 终态；刷新按钮增加固定可访问名称，避免 loading 图标改变定位。增加并通过同步订阅确认时清理超时、终态到达时取消旧 REST 及忽略迟到结果的回归。最后一次全量 Vitest 输出为 `13 passed / 87 passed`，最后一次浏览器输出为 `8 passed`。

Playwright 默认会清理 `test-results`；Vitest JSON 曾由上述命令生成，随后被浏览器运行清理。本记录的最终结果依据实际终端输出。长期保留 JSON 时应与 Playwright 输出使用不同目录。已有浏览器降级场景出现后端 `/ws` 代理 `ECONNREFUSED`，通过 REST Mock 继续完成；FE08 三个实时场景的 WS 由 Playwright 显式 Mock，未依赖真实后端。

## 人工验收

1. 启动实际后端及依赖，为 Vite 启动进程配置与后端一致的 `PROGRESS_SUBSCRIPTION_TOKEN`；前端保持 `VITE_USE_MSW=false`、`VITE_WS_BASE_URL=/ws`，执行 `npm run dev`。
2. 在 ReAct / PER 会话提交一次问题，核对 Execution POST 及幂等键；Network 中 `/ws/progress` 的 SUBSCRIBE 使用知识库 ID 和真实 executionId，收到 SUBSCRIBED 后 GET 带 knowledgeBaseId。
3. 核对进度安全摘要和执行期间输入禁用；最终无需点击刷新即可显示 REST 回答及真实引用。完成后不再为该任务轮询/订阅。
4. 上传文档，检查列表和详情中的解析/索引摘要；文档 READY / FAILED 通知后 GET 更新权威状态并停止订阅。处理失败后重试，检查新流的序列不会沿用旧任务基线。
5. 在开发工具断开网络后恢复，检查有限退避、重连订阅和 REST GET；重连不依赖历史事件回放。关闭页面或切换资源后不应继续保持无用订阅。
6. 通过 Mock 回放同事件、旧序列、跳跃序列及终态；重复/乱序不回退，缺口立即 GET，重复终态只触发一次事件重同步。终态 GET 仍返回处理中时应继续等待并核对状态。
7. 不配置代理令牌或模拟拒绝/REST 失败，核对轮询降级、明确错误和恢复；令牌不出现在浏览器 URL、订阅帧或生产 bundle 中。

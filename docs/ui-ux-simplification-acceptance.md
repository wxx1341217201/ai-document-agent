# UI 与交互精简验收

日期：2026-10-19（Asia/Shanghai）

后续根据用户反馈恢复了第一版首页的视觉层次；当前视觉版本见 [首页视觉恢复与验收](ui-home-visual-refinement.md)。以下保留本轮交互精简的验收记录。

本轮完成前端交互精简，并部署到 http://localhost:8088。视觉规范以根目录 `DESIGN.md` 为准。后端接口及既有业务数据保持原状。

## 已实现

| 区域     | 当前行为                                                                                                            |
| -------- | ------------------------------------------------------------------------------------------------------------------- |
| 工作台   | 展示最近知识库与文档，提供直接提问入口；不默认请求任务列表和系统诊断接口。                                          |
| 主导航   | 保留工作台、知识库；任务记录和系统诊断收进“高级工具”。                                                              |
| 知识库   | 统一卡片展示，编辑、删除进入更多菜单；单页隐藏分页，搜索明确限定当前页。搜索无结果时仍保留多页翻页入口。            |
| 文档列表 | 文件名下显示类型与大小；处理进度仅在处理中显示；失败重试可见，删除保留确认。                                        |
| 上传     | 空知识库直接展示上传区；已有文档时通过“上传文档”展开。                                                              |
| 问答     | 合并为统一会话入口，输入第一个问题后自动创建 Retrieval-First 会话并发送，以问题生成标题。访问空白问答页不创建会话。 |
| 高级会话 | 保留标题与模式选择，可使用 ReAct 和 Plan-Execute-Reflect。                                                          |
| 运行详情 | 异步问答默认收起，按需打开桌面侧栏或手机抽屉；任务详情链接、恢复和取消能力保留。                                    |
| 文档详情 | 内容预览优先；MIME、时间、SHA-256 和处理记录折叠展示。                                                              |
| 引用     | 默认展示文档、页码、原文及查看文档入口；Document ID / Chunk ID 收进技术信息；生产环境不展示未接入的相邻上下文面板。 |
| 回答信息 | 模式、模型和 token 信息折叠展示；真实错误与异步历史恢复限制继续提示。                                               |

## DESIGN.md 核对

- 米白画布 `#f5f5f5`、暖黑主色 `#292524`，白色卡片及细边框。
- Inter 自托管，浏览器实际 `FontFace.status` 为 `loaded`，字重范围 `100 900`。
- 展示标题声明 300 字重；手机页面主标题为 32px。Waldenburg 授权字体文件未包含在仓库，继续使用规范允许的衬线字体回退。
- 40px 胶囊按钮；卡片圆角 16px、输入框圆角 8px，普通输入框与选择框高度 44px。
- 主内容宽度上限 1200px；保留移动导航和减少动态效果支持。淡彩渐变仅用于装饰。
- 真实部署手机检查：视口 390px，页面 `scrollWidth` 375px，无横向溢出。

设计变量位于 `frontend/src/styles/tokens.css`，Ant Design 配置位于 `frontend/src/styles/theme.ts`。

## 自动化验收

| 检查                                                                                      | 结果                                        |
| ----------------------------------------------------------------------------------------- | ------------------------------------------- |
| `npm run lint`                                                                            | 通过                                        |
| `npm run typecheck`                                                                       | 通过；生产镜像构建再次执行通过              |
| 本轮涉及的源文件、样式及 E2E 的 Prettier 检查                                             | 通过                                        |
| `git diff --check`（前端源码与 E2E）                                                      | 通过                                        |
| `npm run test -- --maxWorkers=1 --no-file-parallelism --testTimeout=60000 --reporter=dot` | 30 个文件，159 / 159 通过                   |
| `npm run test:e2e -- --project=chromium --project=chat --workers=1 --reporter=line`       | 13 / 13 通过，使用 REST / WebSocket Mock    |
| `docker compose build frontend`                                                           | 通过                                        |
| `npm run test:deployment -- e2e/deployment.spec.ts --workers=1`                           | 3 / 3 通过，连接真实 Nginx、REST、WebSocket |
| `docker compose exec -T frontend nginx -t`                                                | 通过                                        |

新增行为回归覆盖首次发送、连续 Enter、中文输入法确认、StrictMode 下防重复、创建失败保留草稿、首次回答失败后显式重发、刷新恢复。浏览器回归继续覆盖上传、引用、实时进度、恢复 / 取消及手机布局。

## 真实问答验收

在既有验收知识库 7 中提交一个带 `UI验收20261019` 前缀的临时问题：

1. 直接发送后自动创建会话 196，返回真实模型回答及 `idempotency.txt` 的原文引用。
2. 引用抽屉默认只展示原文、文档与页码状态，技术信息折叠；未编造 TXT 页码。
3. 390px 手机完整刷新后恢复历史，消息仍为一问一答，共 2 条。
4. 通过手机会话列表及删除确认框清理会话 196；列表恢复为空，原有知识库与文档未删除。

截图位于 `frontend/output/playwright/`：

- `ux-deployed-dashboard.png`
- `ux-deployed-chat-mobile.png`
- `ux-deployed-citation-mobile.png`
- `ux-new-chat-desktop.png`、`ux-new-chat-mobile.png`
- Mock 流程另有 `ux-chat-1440.png`、`ux-chat-390.png` 及 Agent / 工作流截图。

## 部署与回退

仅更新 frontend；部署前后 backend 容器 ID 一致。最终七个 Compose 服务均为 healthy。

- 访问地址：http://localhost:8088
- 新镜像：`ai-document-agent-frontend:latest`，镜像摘要前缀 `3b73129a15f9`。
- 发布资源：`index-B5MElaTg.js`、`index-DURiDjet.css`。
- 回退镜像已保留：`ai-document-agent-frontend:ui-before-ux-20261019`。
- 生产环境未注册 Mock Service Worker；深链接刷新、静态缓存、API 转发及 WebSocket 检查通过。

## 现有后端限制

本轮实测仍存在以下接口缺口，未将其伪装成正常数据：

| 请求                                                         | HTTP 状态 |
| ------------------------------------------------------------ | --------- |
| `/api/v1/agent/executions?page=0&size=1`                     | 500       |
| `/api/v1/system/health`                                      | 404       |
| `/api/v1/knowledge-bases/7/documents/9/chunks?page=0&size=1` | 404       |
| `/api/v1/knowledge-bases/7/documents/9/download`             | 404       |

文档预览与原文件下载仍受这些接口限制。异步执行与会话历史的持久关联仍待后端补齐；本轮通过 Mock 验证运行详情、恢复和取消，未新增真实异步模型任务。同步问答的真实创建、回答、引用及刷新恢复已独立验证。

生产构建保留单包大小警告（JS gzip 约 529.32kB）；本轮未做分包重构。Chrome 有字体预加载使用提醒，实际 Inter 已加载，真实问答验收未出现 JavaScript 错误。

# ai-document-agent 前端

FE00–FE04 的 React + TypeScript + Vite 前端实现：工程基线、应用壳、统一 REST 契约层、知识库 CRUD，以及文档上传与处理状态界面。

## 本地运行

```powershell
cd D:\Project\ai-document-agent\frontend
npm install
npm run dev
```

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

# 混合部署：云端前后端 + Windows 依赖服务

适用于当前 2 核 2 GiB ECS 的个人开发与演示。代码编辑、测试和镜像构建在 Windows 完成，云端运行前后端及轻量 HTTPS 网关。Windows 电脑、Docker Desktop、SSH 隧道必须在线；休眠、断网或关闭隧道会影响云端业务。

直接公网访问的配置与验收见 [公网访问说明](hybrid-public-access.md)。下文的 `18088` 入口继续作为本机 SSH 备用入口。

## 当前运行方式与原数据

| 位置 | 运行内容 |
| --- | --- |
| ECS | Spring Boot 后端、Nginx 前端、Caddy HTTPS 网关、上传原文件持久卷 |
| Windows Docker | 现有 MySQL、Redis、RabbitMQ、Qdrant、Elasticsearch 容器 |
| Windows | 开发工具、镜像构建、SSH 隧道 |

2026-10-19 按用户确认，将公网应用对齐到原有数据。现在只有 ECS 业务后端运行；Windows 保留五个依赖服务。原 Windows 前后端容器已停止，并通过 `docker update --restart=no` 禁止自动重启，原卷仍保留。公网可读取原来的 6 个知识库，包括 ID 8《脂砚斋评石头记》。

| 组件 | 当前使用空间 |
| --- | --- |
| MySQL | 原数据库 `ai_document_agent`；云端继续使用普通用户 `ai_document_cloud`，未上传 root 密码 |
| Redis | 原 database 0 |
| RabbitMQ | 原 vhost `/`；云端继续使用普通用户 `ai_document_cloud` |
| Qdrant | 原业务 collection `ai_document_chunks_dashscope_v4`，1024 维；启动时验证已有 collection |
| Elasticsearch | 原业务 index `document_chunks_v1`、alias `document_chunks` |
| 文档原文件 | ECS 新卷 `ai-document-agent-cloud_original_document_data` |

该原文件卷设为 external；后续发布如果发现卷缺失会失败，避免自动创建空卷后继续对外提供不完整的数据。

原数据库引用的 1,844 份原文件来自 Windows 资料目录（5 份）、原 Docker 文档卷（3 份）和基准资料目录（1,836 份）。全部按数据库记录的文件大小和 SHA-256 匹配，再上传并逐份校验。原库中的 1 个 FAILED 文档及其历史记录保持原状态；其余 1,843 个文档原来均为 READY。

不要再启动连接这些空间的第二个 Windows 后端：它会竞争任务且读不到 ECS 后续上传的文件。本机开发如需运行后端，应使用另一套数据库、Redis DB、RabbitMQ vhost 和检索索引。`initialize-hybrid.ps1`、两个发布脚本在 `HYBRID_DATA_MODE=original` 时会检查原 Windows Docker 后端没有运行；这个检查不能阻止手动运行的其他 Java 程序。不要直接执行原根 Compose 的全服务 `up` 来维护当前部署。

此前创建的独立云端库 `ai_document_agent_cloud`、Redis DB 1、云端 vhost/检索索引和旧文档卷均保留，作为此前合成验收数据与回退来源；公网现已切到原库。基准评测专用索引继续保留在原来的评测空间，本次未合并或重建历史检索索引。

## 第一次准备

在 Windows PowerShell 中执行：

```powershell
cd D:\Project\ai-document-agent
./scripts/initialize-hybrid.ps1
```

脚本保留原根 `.env`、已有命名数据卷和 `.env.hybrid.cloud`，只启动五个依赖服务并把发布端口限制为 `127.0.0.1`。当前已对齐的私有配置使用 `HYBRID_DATA_MODE=original`；初始化会验证原数据命名空间及已迁移的 ECS 文件卷，并准备普通云端账号对原库和原 vhost 的访问，不会创建一个空的“原库”替代已有资料。

全新安装且不存在 `.env.hybrid.cloud` 时，脚本仍默认创建 `isolated` 独立测试环境；不会自动迁移用户资料。MySQL/RabbitMQ 凭据随机生成，模型凭据从现有后端有效环境复制，不打印内容，不上传 MySQL root 密码。切换原数据需要先备份、停止重复消费者、迁移并校验原文件，不能只更改数据库名。

初始化可以重复运行：保留已生成的密码、模型配置和 secret，不自动更改已有用户密码。如果原根 `.env` 的 MySQL root 密码与已初始化数据库不一致，先修正原配置再重新初始化；不能用删除数据卷的方式解决。

`.env.hybrid.cloud.example` 仅是字段示例。真正部署使用初始化生成的 `.env.hybrid.cloud`，其文件与 `secrets/`、`artifacts/hybrid/` 均被 Git 忽略。原根 `.env` 后续改了模型 Key 时，应同步编辑云端私有文件；初始化不会覆盖它。

## SSH 与隧道

本机已有 `aliyun` SSH 别名。先验证：

```powershell
ssh aliyun
```

正常登录后退出，再运行只读预检：

```powershell
./scripts/start-hybrid-tunnel.ps1 -CheckOnly
```

若提示 `Permission denied (publickey)`，需要让 ECS 的授权公钥与本机 `~/.ssh/config` 中 `IdentityFile` 对应。可以在阿里云网页终端安装对应 `.pub` 公钥，或在本机配置正确的已有密钥。私钥和密码不要发到聊天中；脚本不会关闭主机身份校验或自动改写 SSH 授权配置。

预检成功后，在一个保持打开的 PowerShell 窗口运行：

```powershell
./scripts/start-hybrid-tunnel.ps1
```

脚本配置心跳和断线重连，`Ctrl+C` 停止。

也可使用受管理的后台模式，窗口会隐藏，日志和进程身份记录保存在 `artifacts/hybrid/`：

```powershell
./scripts/start-hybrid-tunnel.ps1 -Background
# 停止该脚本启动的后台隧道（校验进程身份，不停止其他 SSH 会话）
./scripts/start-hybrid-tunnel.ps1 -Stop
```

后台模式不会添加开机计划任务；Windows 重启后需要重新启动。不要同时运行前台和后台两条相同隧道。

两种模式使用相同的反向转发，全部只在 ECS 的 loopback 上监听：

| ECS 地址 | Windows Docker 已发布地址 |
| --- | --- |
| `127.0.0.1:13307` | `127.0.0.1:3307`，MySQL |
| `127.0.0.1:16380` | `127.0.0.1:6380`，Redis |
| `127.0.0.1:15673` | `127.0.0.1:5672`，RabbitMQ AMQP |
| `127.0.0.1:16333` | `127.0.0.1:6333`，Qdrant HTTP |
| `127.0.0.1:16334` | `127.0.0.1:6334`，Qdrant gRPC |
| `127.0.0.1:19200` | `127.0.0.1:9200`，Elasticsearch |

同一 SSH 连接还把 Windows 的 `127.0.0.1:18088` 转发到 ECS 前端 `127.0.0.1:8088`。本机备用入口为 **http://127.0.0.1:18088**，与公网展示同一份原数据；旧 Windows `8088` 前端已停止。直接公网入口为带访问密码的 **https://47.111.124.154**，配置及验证见 [公网访问说明](hybrid-public-access.md)。

## 构建与发布

保持隧道窗口打开，在另一个 PowerShell 中执行：

```powershell
cd D:\Project\ai-document-agent
./scripts/publish-hybrid.ps1
```

发布脚本从当前工作区构建两个 Linux amd64 镜像，因此包含尚未提交的当前代码。只上传运行镜像、两服务 Compose、私有运行配置和进度令牌；不上传整个 Git 工作区、原根 `.env` 或本地数据库/原文件。

默认远端目录为 `/root/projects/ai-document-agent-cloud`，支持 `-SshHost` 和 `-RemoteDirectory` 参数。上传镜像前先验证 SSH。ECS 通过 SHA-256 校验镜像包，导入镜像，检查六个隧道端口，然后启动并等待前后端健康，最后验证一次真实知识库 REST 请求。

只准备本地部署包、暂不上传：

```powershell
./scripts/publish-hybrid.ps1 -PrepareOnly
```

镜像已经构建且源码没有继续修改时，可加 `-SkipBuild`。私有部署包位于 `artifacts/hybrid/`，包含运行凭据，不能发给别人或放到公共存储。

云端无需安装 Maven、Node.js 或前端开发服务。云端 Compose 使用 Linux host 网络访问宿主机的 loopback 隧道；后端只绑定 `127.0.0.1:18080`，Nginx 只绑定 `127.0.0.1:8088`。不用把数据库端口或 `GatewayPorts` 改成公网可访问。

## 2 GiB 资源设置

- 后端容器上限 1200 MiB，Java 堆上限 512 MiB；前端容器上限 96 MiB。
- 摄取并发、预取和 Agent 执行 worker 均降到单并发；数据库连接池上限 5。
- 检索等待预算为向量 10 秒、关键词 5 秒、总计 15 秒，包含向量模型调用及 SSH 往返；可通过 `HYBRID_RETRIEVAL_*_TIMEOUT` 调整。
- OCR 保留启用，默认渲染 DPI 150、单页最多 400 万像素、页并发 1。细小文字的识别质量可能下降，可在资源允许时调整 DPI；大扫描 PDF 仍需实测。
- 日志轮转；文档原文件持久化。构建留在 Windows，不与云端应用争抢内存。

这些是起始预算，不是所有文件规模的容量保证。实际要观察 `docker stats --no-stream` 和 `free -h`，尤其是 OCR 和并发问答。不能只看容器启动成功就认为完整业务验收通过。

## 验收与日常维护

发布完成后，Windows 浏览器打开 http://127.0.0.1:18088，检查：

1. 新建知识库，上传小 TXT，等待真实 READY。
2. 问一个资料内的问题，核对答案及引用；分别验证 PDF 文本和需要 OCR 的扫描 PDF。
3. 检查实时进度连接，刷新后仍能读取已保存数据。
4. 检查云端峰值内存、日志及重启后的原文件。

部署代理的现成自动化检查（在 `frontend` 目录）可运行：

```powershell
$env:PLAYWRIGHT_BASE_URL = 'http://127.0.0.1:18088'
npm run test:deployment -- --grep Nginx
# 该用例创建合成知识库和文档，并调用真实模型。
npm run test:deployment -- --grep '真实后端：创建 KB' --workers=1
```

ECS 维护命令：

```bash
cd /root/projects/ai-document-agent-cloud
docker compose --env-file .env.hybrid.cloud -f compose.hybrid.cloud.yaml ps
docker compose --env-file .env.hybrid.cloud -f compose.hybrid.cloud.yaml logs --tail=80 backend
docker stats --no-stream
free -h
```

需要停止云端服务时使用 `docker compose ... stop`；不要删除文档卷。云端保存原文件，Windows 保存业务数据库和索引，两端都需要备份。丢失 `.env.hybrid.cloud` 后不要随意重新生成密码覆盖正在使用的配置。

## 验证记录

验证日期：2026-10-19。目标 ECS 为 `47.111.124.154`，运行目录为 `/root/projects/ai-document-agent-cloud`。

| 项目 | 实际结果 |
| --- | --- |
| Windows 初始化 | 重复执行保留原凭据和进度令牌；五个依赖服务健康，发布端口均为 `127.0.0.1`；原命名数据卷保留 |
| 构建 | 当前工作区的两个 Linux amd64 镜像构建成功；后端 Maven 编译打包成功（构建跳过单元测试），前端 TypeScript/Vite 构建成功 |
| 本地镜像预检 | 使用独立临时 Compose 运行新镜像，3 项 Nginx 部署检查通过；临时容器及专属空文件卷已清理 |
| 隧道 | 6 条反向转发及 Windows `18088` 入口已连通；ECS 的服务端口均只监听回环地址 |
| ECS 发布 | 约 405 MiB 镜像包的 SHA-256 校验、镜像导入、前后端健康检查及真实数据库 REST 请求通过 |
| 云端代理验收 | 3/3 通过：SPA/静态资源、真实 REST、WebSocket Upgrade/服务器鉴权/同源检查 |
| 云端完整业务 | 1/1 通过（约 1.1 分钟）：新建知识库、TXT/PDF 达到 READY、真实进度事件、模型回答及有效文档/片段引用、引用和会话刷新恢复、Plan-Execute-Reflect 实时步骤、成功完成及刷新恢复 |
| 持久化 | 强制重建 ECS 后端容器后，全部上传原文件校验和一致；KB 3 的两份文档仍为 READY，真实会话引用仍关联 document 5 / chunk 5，SPA 请求返回 200；前后端健康 |
| 实测资源 | 完整业务验收期间后端约 602 MiB，前端约 4 MiB；后端 cgroup 峰值约 693 MiB；无 OOM、无自动重启，Swap 使用 0，主机可用内存约 361 MiB。这是小合成文档的观测值 |
| 原云端容器 | 用户确认后停止 `dev-mysql`、`dev-redis`；`dev-services_mysql_data`、`dev-services_redis_data` 数据卷仍保留 |

真实业务测试第一次停在引用详情的旧选择器：当前 UI 将 Chunk ID 收在“技术信息”中。验收脚本已改为先核对原文摘录、展开技术信息，再核对对应的 Chunk ID；未更改产品页面。上表的完整业务结果来自修正后成功的一次运行。

上表记录的是切换前独立云端环境的首次验收。其合成知识库保存在 `ai_document_agent_cloud`；这些 ID 不代表当前公网原库中的同名 ID。

不含密钥的最终验证摘要保存在 Git 忽略的 `artifacts/hybrid/cloud-business-evidence.json`。后台隧道在交付时仍运行，Windows 重启后按上文重新启动。

未在本轮运行全部后端单元测试、预置 PAUSED/FAILED 的 Resume 测试、大扫描 PDF OCR 或并发压力测试。2 GiB 设置仍按单人轻量使用；不能把以上小文档验收结果当作大文档容量保证。

## 原数据切换的备份与回退

本次切换备份位于 Windows `artifacts/hybrid/alignment-20261019-183836/`，以及 ECS `/root/projects/ai-document-agent-cloud/backups/alignment-20261019-183836/`。包含切换前两个数据库的完整 SQL 备份、原文件压缩包及校验清单、旧云端文件压缩包（ECS）、私有配置前后版本。所有备份均为私有资料，不加入 Git。

回退旧独立云端环境时，先停止 ECS 后端，将该备份中的 `.env.hybrid.cloud.before` 和 `compose.hybrid.cloud.yaml.before` 恢复到远端部署目录，然后使用原有公网 Compose 覆盖层启动前后端；同步恢复本机私有配置，避免下次发布再次切回。旧的 `ai-document-agent-cloud_document_data` 卷和 `ai_document_agent_cloud` 数据库未删除。公网网关账号、证书卷不需要回退。

不要在回退时导入 SQL 备份覆盖正在使用的数据库。SQL 备份用于故障恢复；常规部署回退只切连接配置和文件卷。切换之后的新上传文件与数据需要单独保留，不应被旧快照覆盖。

原数据切换完成后的实际验收：

| 检查 | 结果 |
| --- | --- |
| 公网知识库 | 原库 6 个知识库全部可见，ID 为 1、2、7、8、9、10 |
| 文档信息 | 通过真实公网 API 分页读取全部 1,844 条记录，ID、知识库归属、名称、大小、状态均与原库匹配 |
| 原文件 | ECS 实际非 root 后端用户校验 1,844 份 SHA-256 全部通过；后端再次重建后复验仍全部一致 |
| 原书问答 | 对知识库 8 的真实问题回答“青埂峰下”，引用 document 15 / chunk 356；最终检索 `degraded=false`，约 14.28 秒 |
| 公网回归 | 最终配置重建后 4/4 通过（约 38.4 秒）：访问保护、SPA/静态资源、真实 REST、真实 WebSocket/同源检查 |
| 备用入口 | `127.0.0.1:18088` 返回同一套 6 个知识库 |
| 单后端 | Windows 仅五个依赖容器运行，两个旧应用容器为 exited / restart=no；原两条业务队列各有一个消费者、无待处理或未确认消息 |
| 重复维护 | 原数据模式重复初始化保留私有配置；Windows Compose 和部署 PowerShell 语法校验通过 |
| 最终健康 | ECS 三个容器健康；后端约 565 MiB、前端约 4 MiB、网关约 12 MiB；主机可用约 394 MiB，Swap 使用 0，无 OOM/自动重启 |

本次未新增验收知识库，也未重建或合并原有历史/基准索引；检索业务验证使用原书。切换摘要保存在 Git 忽略的 `artifacts/hybrid/original-data-alignment.json`，详细 API 与问答证据在上述备份目录。

## 工作台最近文档加载修复（2026-10-19）

原工作台为展示最近 5 份文档，会逐页读取 6 个知识库中的全部 1,844 份文档，合计 97 次文档请求。现使用 `GET /api/v1/documents/recent` 一次返回最多 5 条元数据，按 `updated_at DESC, id DESC` 稳定排序。Flyway V14 增加 `(updated_at, id)` 索引，实际 MySQL 查询计划使用反向索引扫描，读取 5 行。

最近文档卡片独立于知识库列表加载；失败后显示错误和重试按钮。前端 7 项、后端 19 项相关检查通过；真实公网页面验证只发出 1 次文档请求，浏览器中接口约 1.18 秒，直接公网会话请求约 0.62–0.69 秒。其他 5 项公网登录、REST、静态资源和 WebSocket 回归通过。

发布前后全部 1,844 条文档元数据快照 SHA-256 一致，原书 document 15 仍为 READY。云端三个容器健康，网关容器及浏览器会话保持。修复记录、备份及浏览器截图在 Git 忽略的 `artifacts/hybrid/recent-documents-20261019-200815/`。

## 工作台独立滚动与按需分页（2026-10-19）

最近知识库和最近文档现在各有独立的纵向滚动条，标题栏保持固定；长名称、描述与连续哈希在卡片内换行。样式沿用 `DESIGN.md` 的中性色、间距和圆角。

两个列表分别使用 `GET /api/v1/dashboard/knowledge-bases?page=0&size=5` 和 `GET /api/v1/dashboard/documents?page=0&size=5`。首次各取一页，接近底部时才读取下一页，也可点击加载更多；失败时保留已有记录并提供重试。数据库按 `updated_at DESC, id DESC` 稳定排序，闭合投影只选择列表展示字段，旧 `/documents/recent` 保留兼容。文档继续使用 V14 索引，V15 增加知识库排序索引；原库只有 6 个知识库，优化器仍可能选择小表排序。

本次前端 10 项、后端 24 项相关测试及前端类型、静态检查通过。真实公网 7 项只读回归全部通过，包括独立滚轮加载下一页、标题位置固定、长描述不溢出、Cookie 会话、静态资源、REST 和 WebSocket；390px 移动端也未出现横向溢出。原有 1,844 条文档及 6 个知识库的元数据快照 SHA-256 完全一致：`c8cae04c2d4d97e52fd4665e11ed24f6a94791207cfeea52a00e6c6a58580b03`。

仅更新云端前后端应用镜像，三个容器健康且无自动重启，网关保持运行。旧镜像、结构备份及发布证据在 Git 忽略的 `artifacts/hybrid/dashboard-scroll-20261019-233918/`，云端对应 `backups/dashboard-scroll-20261019-233918/`；桌面和移动端截图在 `frontend/output/playwright/dashboard-scroll/`。

## 任务记录与系统诊断补齐（2026-10-20）

已实现 `GET /api/v1/agent/executions` 的后端分页及状态、模式组合筛选。默认每页 20 条、最大 100 条，按 `updated_at DESC, execution_id DESC` 稳定排序；闭合投影仅选择 8 个列表字段，不读取问题、结果 JSON、请求键或执行令牌。V16 添加全局、状态及模式排序索引，复用已有详情、恢复和取消功能。不支持的 HTTP 方法返回明确的 405，避免误报为系统繁忙。

`GET /api/v1/system/health` 并行检查 MySQL 连接、Redis PING、RabbitMQ channel、Qdrant health RPC、Elasticsearch ping。整体等待最多 3 秒，结果缓存 5 秒；仅返回固定组件名称和 `UP` / `DOWN` / `UNKNOWN`。失败和超时只影响对应状态，页面保留上次成功结果并可重试，响应不包含地址、凭据或异常详情。

前后端各 26 项相关测试、前端类型和静态检查通过；真实公网 10 项验收全部通过，包括任务分页/筛选/详情、五项依赖状态/刷新，以及原有工作台、Cookie 会话、静态资源、REST 和 WebSocket。浏览器额外核对原有 11 条任务、Plan-Execute-Reflect 的 4 条记录及组合筛选空态；当前五项依赖均为 UP。

发布前后原有 1,844 条文档、6 个知识库、11 条任务的元数据快照一致，SHA-256 为 `439214a89b8abbb4663c287f411381b9014aec3ecc85bdf7815ea970a7ca5c8b`。仅更新前后端应用镜像，三个云端容器健康，网关容器与既有登录会话保留。备份与回滚镜像记录在 Git 忽略的 `artifacts/hybrid/advanced-tools-20261020-000755/` 以及云端对应 `backups/advanced-tools-20261020-000755/`；页面截图在 `frontend/output/playwright/advanced-tools/`。

## 文档正文与原文件接口上线（2026-10-20）

文档元数据及 READY 状态此前已存在，但缺少正文和原文件的读取路由，详情页因此收到 404。本次补齐 `GET /api/v1/knowledge-bases/{kbId}/documents/{documentId}/chunks?page=0&size=50` 与 `/download`。

正文查询先校验知识库及文档归属，按 `chunk_index ASC` 在数据库分页；默认 50 条、最大 100 条，闭合投影仅读取页面使用的 6 个片段字段。复用既有文档片段顺序索引，无新增数据库迁移。下载直接流式读取持久化原文件，保留 TXT 的原编码及 TXT/PDF/DOCX 原始字节，提供 UTF-8 附件文件名、正确 MIME 和禁止缓存响应；错误即使面对仅接受二进制的客户端也返回明确 JSON。Nginx 隐藏后端同名安全头后统一输出一份 `X-Content-Type-Options: nosniff`，避免重复。

后端 33 项、前端 6 项相关测试及生产类型检查、Nginx 渲染后语法检查通过。最终版本的真实公网只读验收 13/13 通过，覆盖 1,125 个原书片段的前页、后页、末页、越界和归属校验，4 份 TXT/PDF/DOCX 的下载大小与 SHA-256、实际浏览器下载和原书翻页，以及原有任务、诊断、工作台、登录会话和 WebSocket。发布前后 1,844 条文档、6 个知识库及 3,268 个片段（含正文内容）的快照 SHA-256 相同：`4dcde8e894db1297b600ca0d5921e02d95700ae1fdcc14f6e42b6f63f7fc3e1a`。

首次镜像导入期间 ECS 资源占用过高并失去响应，用户重启实例后恢复连接。恢复时发现已有的 2 GiB `/swapfile` 尚未启用；已启用并写入 `/etc/fstab`，systemd 生成的交换单元为 active。再次导入前先停止旧后端释放内存，导入及启动成功；最终三个容器健康、五项依赖均为 UP，后端约 524 MiB、主机可用内存约 657 MiB、交换空间使用 0，本次发布后没有 OOM 或自动重启。

本次更新后端和前端代理镜像，网关容器及登录会话保留。Windows 发布备份在 Git 忽略的 `artifacts/hybrid/document-content-20261020-003230/`，ECS 对应目录为 `/root/projects/ai-document-agent-cloud/backups/document-content-20261020-003230/`；旧镜像分别保留为 `ai-document-agent-backend:before-document-content-20261020-003230` 与 `ai-document-agent-frontend:before-document-content-20261020-003230`，回退应用使用这些镜像及原 Compose 配置。发布日志、原文件校验、数据快照和交换空间配置备份均在该目录；最终页面截图为 `frontend/output/playwright/document-content/deployed-content.png`。

## 云端 Agent 工具超时预算修复（2026-10-20）

任务 `99d49b1f-1d54-4729-b8d9-63c24e75f906` 在知识库 8 的 `searchKnowledgeBase` 上以 `TOOL_TIMEOUT` 失败，原记录耗时 5,005 ms。云端混合检索总预算为 15 秒，查询扩展等待上限为 30 秒，ReAct/Plan 外层工具却沿用 5 秒默认值；外层提前取消任务，取消时还可能中断正在执行的 JDBC 读取并出现 `Closed by interrupt`。

`compose.hybrid.cloud.yaml` 现显式设置 `RAG_QUERY_EXPANSION_TIMEOUT=30s`，两个模式的 `AGENT_*_TOOL_TIMEOUT=35s`、`AGENT_*_MODEL_TIMEOUT=35s`，每次尝试的整体截止时间分别为 `AGENT_REACT_DEADLINE=120s` 和 `AGENT_PLAN_DEADLINE=180s`。工具和模型等待仍取单项上限与剩余总时间的较小值；24 小时恢复窗口继续使用原状态机。参数可通过同名环境变量覆盖，示例同步到 `.env.hybrid.cloud.example`。

已验证渲染后的 Compose 默认配置及显式覆盖；ReAct、Plan、ExecutionRunner 共 40 项现有回归通过，包含超时和整体截止时间安全停止。确认没有 PENDING/RUNNING 任务后仅更新云端 Compose 并重建后端，应用镜像、前端、网关、凭据与数据卷保留，实际运行环境的 7 项预算与预期一致。

在页面通过“Resume · 恢复任务”重试同一个失败任务后，状态持久化为 SUCCEEDED，版本从 4 增至 12。新的两次搜索耗时 5,428 ms 和 2,348 ms，生成 467 字回答及 3 条引用；三条引用逐一核对知识库 8 / document 15 的 chunk 525、368、526，quote 均与原文一致。HTTPS 公网读取也确认同一成功结果，原 5,005 ms 失败工具记录继续作为历史保留。

修复前后知识库、文档及正文片段快照 SHA-256 完全相同：`4dcde8e894db1297b600ca0d5921e02d95700ae1fdcc14f6e42b6f63f7fc3e1a`。三个容器健康，五项依赖为 UP；实测后端约 595 MiB、主机可用内存约 572 MiB，交换空间使用 0，无 OOM 或自动重启。配置备份、任务前后结果、原文核对与回滚脚本在 Git 忽略的 `artifacts/hybrid/agent-timeouts-20261020-012035/` 及 ECS 对应 `backups/agent-timeouts-20261020-012035/`。回退时恢复其中的 `compose.before.yaml` 为云端主 Compose 并仅重建后端。页面证据在 `frontend/output/playwright/agent-timeouts/resumed-task.png`。

## 官方依据

- [OpenSSH 反向端口转发](https://man.openbsd.org/ssh#R)
- [Docker Linux host 网络](https://docs.docker.com/engine/network/drivers/host/)
- [Compose 合并及端口替换规则](https://docs.docker.com/compose/how-tos/multiple-compose-files/merge/)

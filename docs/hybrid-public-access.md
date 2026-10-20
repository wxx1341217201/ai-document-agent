# 混合部署的公网访问

公网入口：`https://47.111.124.154`。HTTP 的 80 端口跳转到 HTTPS 的 443 端口。

打开页面后浏览器会要求用户名和密码。当前用户名为 `admin134`，实际密码保存在本机 Git 忽略的 `secrets/hybrid_public_access.json`，其中 `password` 字段可以复制到登录框。不要把这个文件加入版本控制或发给其他人。ECS 只接收密码的 bcrypt hash，不接收该 JSON 中的明文密码。发布脚本保留已设置的账号和密码。

网关的会话修复让正确登录后的页面、API 和 WebSocket 共用 `__Host-aida_session` Cookie，不再仅依赖浏览器对 Basic 凭据的缓存。该 Cookie 使用随机 256 位令牌、`Secure`、`HttpOnly`、`SameSite=Strict`，限定当前主机和 `/` 路径；不设置持久化期限。浏览器会话恢复功能可能保留它。Cookie 不写入 localStorage，也不转发给应用后端。

这是当前单账号网关的共享会话凭据。正常发布保留令牌；用户名或密码 hash 变化时，发布脚本会轮换令牌，使旧会话失效。错误密码、伪造 Cookie、缺失会话密钥均不能取得访问权限。修改凭据时应同时维护其 bcrypt hash，再通过发布脚本同步，不能只手改 ECS 的密码 hash 而保留旧会话令牌。

## 运行关系

```text
浏览器 → ECS HTTPS 网关（443，访问密码）
             → ECS Nginx（127.0.0.1:8088）
                 → ECS 后端（127.0.0.1:18080）
                     → SSH 反向转发 → Windows 上的五个依赖服务
```

浏览器直接连接公网 IP，无需配置 SSH 或使用 `127.0.0.1:18088`。Windows 电脑上的 Docker 和后台依赖隧道仍需在线。电脑断网、休眠或关机时，网页可能仍能显示，但数据库、检索和摄取等业务会受影响。

原来的 `http://127.0.0.1:18088` 保留为本机私有备用入口。网关的认证覆盖公网页面、API 和 WebSocket；前端继续在服务端为进度 WebSocket 注入独立令牌。

2026-10-19 已按用户确认对齐原有数据：公网后端现在连接 Windows 上的原数据库 `ai_document_agent`、Redis DB 0、原队列及业务检索索引，原文档已复制到 ECS 的新持久卷。原 Windows 前后端已停止，五个依赖服务继续运行；公网和 `18088` 入口访问同一套数据。原库中的《脂砚斋评石头记》使用知识库 ID 8。备份及回退见 [混合部署说明](hybrid-deployment.md)。

## 发布

在已经完成私有混合部署的 Windows 项目目录中执行：

```powershell
docker pull --platform linux/amd64 caddy:2.11.7-alpine
./scripts/publish-hybrid-public.ps1
```

脚本构建前端、打包官方 Caddy 镜像并上传到已有 ECS 目录，加入公网 Compose 覆盖配置。重复发布保留同一份访问凭据和 TLS 持久卷。模型 Key、数据库密码及文档卷沿用已有混合部署，不由此脚本重新生成或迁移。

只准备本地部署包可以使用 `-PrepareOnly`。私有包位于 Git 忽略的 `artifacts/hybrid/public/`。

云端安全组需要允许公网入方向的 TCP 80、443；主机防火墙如启用，也需要允许这两个端口。其他项目端口保持内部访问。HTTPS 使用 Let's Encrypt 的 IP 地址短期证书，Caddy 自动申请和续期，证书和 ACME 账户保存在 `public_tls_data` 卷中，不能删除该卷。

普通应用更新继续使用 `publish-hybrid.ps1`；它上传的 Linux 启动脚本会在 ECS 已有公网配置时保留公网覆盖层。网关配置变更使用 `publish-hybrid-public.ps1`。

只更新网关配置、不构建镜像或重建业务前后端：

```powershell
./scripts/publish-hybrid-public.ps1 -GatewayOnly
```

这个模式要求 ECS 已有 Caddy 镜像及完整混合部署。可加 `-PrepareOnly` 先准备并检查本机部署文件；发布 SSH 连接带心跳，避免网络中断后无限等待。

## 验收与维护

在 `frontend` 目录运行公网验收（凭据仅在测试进程内读取）：

```powershell
$env:PLAYWRIGHT_BASE_URL = 'https://47.111.124.154'
$env:PLAYWRIGHT_PUBLIC_CREDENTIALS_FILE = 'D:/Project/ai-document-agent/secrets/hybrid_public_access.json'
npm run test:deployment -- --grep 'Nginx|公网入口|真实后端：创建 KB' --workers=1
```

测试包含 HTTP 跳转、匿名和错误密码被拒绝、登录后页面/REST/WebSocket，以及创建合成知识库、上传、模型引用和 Agent 执行。完整业务用例会调用真实模型。

会话回归使用另一个只携带 Cookie、没有 HTTP Basic 缓存或账号密码的浏览器上下文，验证 API、刷新及三次 WebSocket 建连；可选调用已有知识库进行真实问答，不创建测试知识库：

```powershell
npm run test:deployment -- --grep '公网会话' --workers=1
# 可选：仅在需要验证真实问题时设置；会调用模型。
$env:PLAYWRIGHT_SESSION_QUERY_KB_ID = '8'
$env:PLAYWRIGHT_SESSION_QUERY_QUESTION = '女娲补天后剩下的石头被弃在什么地方？'
npm run test:deployment -- --grep '公网会话' --workers=1
```

同一会话回归可用 `--browser=webkit` 检查 WebKit 内核。Windows WebKit 测试运行时在本次环境中将 Cookie API 的 SameSite 读数返回为 `None`，但实际登录响应头为 `SameSite=Strict`；测试直接核对响应头的策略，并继续执行无账号密码的 API、刷新及三次实时连接。未将服务器策略改为 None；这个运行时验证不等同于 iPhone 实机测试。

独立的本机网关认证检查（项目根目录）：

```powershell
./scripts/test-hybrid-public-auth.ps1
```

该检查只启动临时回环端口容器，以固定响应替代应用，覆盖认证顺序、Cookie 属性、错误密码、伪造 Cookie 和缺少密钥时的拒绝；结束后清理临时容器，不连接数据库或模型。

ECS 上查看网关：

```bash
cd /root/projects/ai-document-agent-cloud
docker compose --env-file .env.hybrid.cloud -f compose.hybrid.cloud.yaml -f compose.hybrid.public.yaml ps
docker compose --env-file .env.hybrid.cloud -f compose.hybrid.cloud.yaml -f compose.hybrid.public.yaml logs --tail=60 gateway
```

临时关闭公网入口可用同样的 Compose 参数执行 `stop gateway`，不会删除文档、数据库或证书卷。后续执行完整发布会重新启动网关。

## 本轮验证

验证日期：2026-10-19（北京时间）。用户在阿里云安全组中放行 TCP 80、443 后，从 Windows 直接连接 ECS 公网 IP 完成以下检查：

| 检查 | 实际结果 |
| --- | --- |
| HTTP 与 TLS | HTTP 返回 308 并跳转到 HTTPS；Windows 系统正常校验证书，无需忽略证书错误 |
| 证书 | Let's Encrypt 正式 IP 证书，SAN 为 `47.111.124.154`；首张证书有效期至 2026-10-26 09:08（北京时间），Caddy 自动续期 |
| 访问控制 | 独立匿名请求及错误密码检查 1/1 通过：页面、API、WebSocket 均不能匿名访问；正确密码的真实 API 返回 200 / SUCCESS |
| 页面与代理 | 公网 SPA 深链接刷新、静态资源、真实 REST、WebSocket Upgrade 及跨站 Origin 拒绝均通过 |
| 完整真实业务 | 公网创建知识库、TXT/PDF 摄取到 READY、进度事件、真实模型引用、会话刷新、Plan-Execute-Reflect 实时步骤、成功完成和刷新恢复均通过 |
| 私有备用入口 | 更新后的 `18088` 入口 3/3 代理回归检查通过 |
| 资源与健康 | 业务验收后三个容器均健康；后端约 602 MiB、前端约 4 MiB、网关约 13 MiB，主机可用约 362 MiB，Swap 使用 0 |

公网页面/代理和完整业务在同一次运行中 4 项通过（约 2.4 分钟）；访问控制用例因测试框架会继承全局凭据，修正为显式清空匿名上下文的凭据后独立 1 项通过（约 3.9 秒）。没有跳过 TLS 验证、拦截 REST/WebSocket 或模拟模型响应。

切换前的完整公网业务对应旧独立数据库 `ai_document_agent_cloud` 中的知识库 ID 4、文档 ID 7/8 和会话 ID 5/6，均为合成验收资料。该环境现作为备份保留，这些 ID 不代表当前公网原库中的数据。不含密钥的历史摘要保存在 Git 忽略的 `artifacts/hybrid/public-verification.json`。

同日按用户要求将公网登录用户名更新为 `admin134`，密码保存在上述私有凭据文件。新账号真实 API 返回 200 / SUCCESS，原账号及匿名请求返回 401；新凭据的访问控制与浏览器 WebSocket 检查 2/2 通过（约 19.3 秒）。摘要保存在 Git 忽略的 `artifacts/hybrid/public-credential-update-verification.json`。本机凭据、私有部署包及 ECS 网关配置已同步，后续发布保留自定义账号密码。

部署中同时修复了三个实际问题：裸 IP 客户端可能不发送 SNI，网关现指定默认 IP 服务器名；代理保留原访问主机、端口和 HTTPS 协议，保证同源检查正确；Windows CRLF 转为 LF 后再生成 bcrypt hash，避免把回车误当作密码内容。相关修复已写入配置、镜像和发布脚本。

同日完成原数据对齐后再次验证：公网显示原来的 6 个知识库，1,844 条文档信息与原数据库一致；全部原文件已在 ECS 逐份校验并经后端重建复验。知识库 8《脂砚斋评石头记》真实问答成功，引用原文档 15 / chunk 356，最终检索未降级。公网访问控制、SPA、REST 与 WebSocket 4/4 通过（约 38.4 秒）。访问账号、密码及证书配置保持当前设置。

### 会话修复的交付状态（2026-10-19）

已完成公网部署和验证。旧配置的会话回归明确失败于“登录未签发浏览器会话”；修复后，本机实际网关认证 7/7 通过，TypeScript 与该回归文件的 ESLint 检查通过。Chromium 公网验收 5/5 通过（约 1.1 分钟），包含只用 Cookie 的真实原书问答、页面刷新、三次 WebSocket 建连，以及匿名/错误密码拒绝和代理回归。WebKit 会话回归 1/1 通过（约 24.4 秒），同样不使用 HTTP Basic 缓存或账号密码完成 API、刷新及三次实时连接。

配置备份位于本机 `artifacts/hybrid/auth-session-20261019-190314/` 和 ECS `/root/projects/ai-document-agent-cloud/backups/auth-session-20261019-190314/`。本次只重建网关，前后端的容器 ID 和启动时间与修复前一致，原数据及证书卷保留。用户名和密码未变，重复发布保留会话令牌。验收摘要见 `artifacts/hybrid/public-session-verification.json`。

第一次发布期间曾出现 SSH banner 握手超时和 HTTPS 无响应，用户也确认无法访问；连接随后恢复，实例未重启。恢复后观察到近期负载较高、当前 CPU 已空闲、Swap 使用 0，未查到相应 OOM/内核 I/O 错误记录；尚不能据此确定失联原因。重试网关发布后，公网、SSH 依赖隧道及原知识库均已恢复并验证。最终三个容器健康，后端约 616 MiB、前端约 3.4 MiB、网关约 24 MiB，主机可用约 368 MiB。若已经排队的旧认证弹框仍停留，可关闭旧项目标签页，重新打开 HTTPS 项目页后登录一次。

## 官方参考

- [Caddy Basic 认证与 401 行为](https://caddyserver.com/docs/caddyfile/directives/basic_auth)
- [Caddy route 保持认证和响应头执行顺序](https://caddyserver.com/docs/caddyfile/directives/route)
- [浏览器 Cookie 的安全属性及会话生命周期](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Set-Cookie)

- [Let's Encrypt IP 地址证书及自动续期要求](https://letsencrypt.org/2026/01/15/6day-and-ip-general-availability)
- [Caddy ACME profile 配置](https://caddyserver.com/docs/caddyfile/directives/tls)
- [Caddy 访问密码](https://caddyserver.com/docs/caddyfile/directives/basic_auth)
- [阿里云安全组 Web 访问配置](https://help.aliyun.com/zh/ecs/user-guide/security-groups-for-different-use-cases)

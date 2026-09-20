# 开发进度

## 当前基线（M00）

- 技术栈：Java 21、Maven Wrapper 3.3.4（Maven 3.9.16）、Spring Boot 4.1.1、Spring AI 2.0.1。
- 本地基础设施：MySQL 8.4 映射至 `localhost:3307`，Redis 8 映射至 `localhost:6380`，应用端口为 `8080`。
- 数据库：Flyway 已启用，当前唯一迁移为 `V1__create_knowledge_base.sql`；新迁移必须以新的版本文件新增，不能修改 V1。
- 配置安全：本地机密放在未追踪的 `.env`；仓库提供 `.env.example` 模板。
- 连接约束：MySQL、Redis、RabbitMQ 和 Elasticsearch 已配置有限的连接或 socket 超时；RabbitMQ、Elasticsearch 的健康检查在当前默认 profile 禁用，因为它们不在本模块的 Compose 编排中。
- 测试：`test` profile 会隔离 MySQL、Redis、RabbitMQ、Elasticsearch 和 Qdrant，提供不依赖外部服务的最小 Spring 上下文启动测试。

## 从零启动

1. 在 Windows PowerShell 中复制环境变量模板：`Copy-Item .env.example .env`，然后只在 `.env` 中填写本地密码和 OpenAI API Key。
2. 启动当前基线依赖：`docker compose --env-file .env up -d mysql redis`。
3. 等待容器健康：`docker compose --env-file .env ps`。
4. 启动应用：`.\mvnw.cmd spring-boot:run`。
5. 验证基础设施健康：`Invoke-RestMethod http://localhost:8080/actuator/health`。预期响应中的 `db` 和 `redis` 均为 `UP`。

## 验证命令

```powershell
.\mvnw.cmd -DskipTests compile
.\mvnw.cmd test
docker compose --env-file .env up -d mysql redis
Invoke-RestMethod http://localhost:8080/actuator/health
```

## 已知问题与待启动组件

- 当前 `compose.yaml` 有意只编排 MySQL 和 Redis。RabbitMQ、Qdrant、Elasticsearch 仍是后续模块需要的依赖，尚未启动，也没有业务代码；不得为了消除连接告警而从 `pom.xml` 删除它们。Qdrant 自动配置、以及 RabbitMQ 与 Elasticsearch 的 health contributor 会在相应组件接入时重新启用。
- 默认应用配置指向本地 MySQL 和 Redis。只有它们启动且 `.env` 已填写后，默认 profile 才能通过 Flyway 初始化并显示对应健康状态。
- 本工作目录当前没有 `.git` 元数据，因而本次无法运行 `git diff` 或用 Git 证明 `.env` 未被历史追踪；`.gitignore` 已包含 `.env`。初始化或恢复 Git 仓库后，应执行 `git ls-files --error-unmatch .env`，该命令应返回非零。

## 下一模块

开始知识库 CRUD 前，需要确认本地 Docker 守护进程可用，并以实际 MySQL 数据卷的 Flyway history 确认 V1 是否已执行。

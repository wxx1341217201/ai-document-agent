# 后端架构调整与验收记录（2026-10-19）

已完成审查提出的三处调整：客户端接入官方配置与观测、Rabbit 工厂继承 Boot 配置、基准 Controller 的应用编排下移。未升级依赖。

## 实现

| 调整 | 实现与结果 |
|---|---|
| ChatClient | RagConfiguration 注入 Spring AI 自动配置的 ChatClient.Builder，保留 RagChatOptions 的业务默认选项，使全局 Builder 定制与观测进入实际问答客户端。 |
| 向量存储 | 手动装配的 QdrantVectorStore 接入 ObservationRegistry、可选 VectorStoreObservationConvention 和 BatchingStrategy；没有额外配置时使用官方默认批处理策略。仍先执行 collection 兼容性预检，再构建存储。 |
| HTTP 重排 | RerankingConfiguration 注入 Boot 的 RestClient.Builder、ClientHttpRequestFactoryBuilder 和 HttpClientSettings；从全局 HTTP 设置派生重排专用连接/读取超时，保留其他设置和客户端定制。HttpCrossEncoderReranker 接收已装配的 RestClient。 |
| Rabbit 工厂 | 先由 SimpleRabbitListenerContainerFactoryConfigurer.configure 初始化，再应用摄取专用 AUTO 确认、不重新入队、预取和并发配置。此工厂清空容器内重试拦截器，继续由持久化 Outbox 与重试队列负责重试，避免两套重试叠加。 |
| 基准 Controller | 新增 RabbitBenchmarkApplicationService，承接阶段切换、知识库轮次隔离、串行锁、摄取执行、SQL 查询、指标采样和索引校验。Controller 仅转换 MultipartFile、调用应用服务、适配响应与请求计时属性。RabbitBenchmarkNode 的显式导入已同步更新。 |

代码入口：

- [RagConfiguration](D:/Project/ai-document-agent/src/main/java/com/wxx/aidocumentagent/rag/RagConfiguration.java)
- [VectorConfiguration](D:/Project/ai-document-agent/src/main/java/com/wxx/aidocumentagent/vector/VectorConfiguration.java)
- [RerankingConfiguration](D:/Project/ai-document-agent/src/main/java/com/wxx/aidocumentagent/retrieval/rerank/RerankingConfiguration.java)
- [IngestionConfiguration](D:/Project/ai-document-agent/src/main/java/com/wxx/aidocumentagent/ingestion/IngestionConfiguration.java)
- [RabbitBenchmarkController](D:/Project/ai-document-agent/src/main/java/com/wxx/aidocumentagent/ingestion/benchmark/RabbitBenchmarkController.java)
- [RabbitBenchmarkApplicationService](D:/Project/ai-document-agent/src/main/java/com/wxx/aidocumentagent/ingestion/benchmark/RabbitBenchmarkApplicationService.java)

`/benchmark` 的路径、请求参数和 JSON 字段保持一致。基准 Controller 与应用服务均受 `app.benchmark.rabbitmq.node=true` 控制，默认不装配。同步流程未增加外层事务；原有事务边界和 Outbox 状态转换保持原样。

ChatClient 与 VectorStore 的观测是否导出，仍由环境中的 ObservationRegistry、Handler 和监控配置决定；Rabbit 容器遵从 Boot 的观测开关。

## 验收

环境：Java **21.0.12**，Spring Boot **4.1.1**，Spring AI **2.0.1**。

```powershell
.\mvnw.cmd -o '-Dtest=RagConfigurationStartupTest,RagConfigurationOptionsTest,SpringAiAgentModelOptionsTest,VectorConfigurationTest,IngestionRabbitTopologyTest,RerankingConfigurationTest,HttpCrossEncoderRerankerTest,CohereRerankerTest,DashScopeRerankerTest,CircuitBreakingRerankerTest,RerankingApplicationServiceTest,RabbitBenchmarkApplicationServiceTest,RabbitBenchmarkMetricsTest,RabbitBenchmarkRequestFilterTest,RabbitBenchmarkEvaluatorTest' test
```

最终结果：**15 个测试类、56 个用例，0 failures、0 errors、0 skipped，BUILD SUCCESS**。同时完成全部 379 个主 Java 源码和 118 个测试源码的编译。

新增或扩展的回归检查覆盖：

- 实际问答客户端继承全局 system 定制，并产生 `spring.ai.chat.client` 观测事件。
- Qdrant 写入使用注入的批处理策略和自定义观测约定；无可选配置时能使用默认策略；集合预检失败时不开放写入。
- 重排 HTTP 请求携带全局客户端定制的请求头，使用重排专用超时，同时保留全局重定向策略。
- Rabbit 工厂继承 `auto-startup` 和观测配置，即使全局开启容器重试，此摄取工厂仍保留既有 AUTO 确认与 Outbox 重试语义。
- 基准同步处理抛错后释放锁，后续线程可继续提交；异步提交按阶段和轮次隔离；未排空阶段不得切换。
- Multipart 上传响应字段、上传内容、响应序列化后的确认计时，以及默认关闭基准组件的行为保持正确。
- Cohere、DashScope、通用重排、熔断与降级，以及 Agent 模型选项的既有回归测试通过。

测试期间修正了新增测试中的类型导入和模拟模型/HTTP 响应配置，最终结果以上述完整重跑为准。运行时允许 Mockito 在本机附加 JVM，以避开 Windows 沙箱的附加权限限制。

验收范围为本地编译、上下文装配、Mock HTTP 和单元回归；未执行真实数据库、RabbitMQ、Qdrant、远端模型联调或性能基准，也未部署。

## 官方接口依据

已核对本地 Maven 中 Boot 4.1.1 / Spring AI 2.0.1 的实际类型与方法签名，参考：

- [Spring AI ChatClient 配置](https://docs.spring.io/spring-ai/reference/api/chatclient.html)
- [Spring Boot REST 客户端配置](https://docs.spring.io/spring-boot/reference/io/rest-client.html)
- [Spring Boot AMQP 配置](https://docs.spring.io/spring-boot/reference/messaging/amqp.html)

调整前完整源码审查及历史 SHA-256 清单继续保存在 [审查报告](D:/Project/ai-document-agent/docs/backend-architecture-review-2026-10-19.md) 和 [审查源码清单](D:/Project/ai-document-agent/docs/backend-architecture-review-2026-10-19-files.csv)。该清单描述调整前快照，不代表修改后源码哈希。

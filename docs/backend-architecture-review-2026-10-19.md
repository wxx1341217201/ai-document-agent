# 后端架构规则审查（2026-10-19）

> 本文与源码清单保留调整前的审查快照。审查提出的三处调整现已完成，变更及验收结果见 [后端架构调整与验收记录](D:/Project/ai-document-agent/docs/backend-architecture-adjustments-2026-10-19.md)。

结论：**整体遵循，但尚未完全落实。正式业务 Controller 的职责控制较好；主要偏差在自动配置的接入，以及一个基准测试 Controller。现有业务状态机和证据约束有保留理由，不建议整体重写。**

## 审查范围与方法

- 逐文件阅读 `src/main/java` 下全部 **378 个 Java 文件，共 27,550 行**；其中正式功能及公共代码 329 个文件、20,797 行，基准测试代码 49 个文件、6,753 行。
- 核对 `pom.xml`、5 份主资源 YAML 配置、测试 profile 和全部 13 份 Flyway 迁移。
- 索引 116 个测试源码文件；本次执行与规则相关的 7 个测试类，**未宣称逐行审阅全部测试或运行全量集成测试**。
- 按知识库管理、上传与摄取、混合检索与 RAG、会话、三种 Agent、持久执行与恢复、WebSocket 进度、基准工具的调用链检查。
- 对照 Spring Boot 4.1.1 / Spring AI 2.0.1 官方文档，并检查本地 2.0.1 JAR 字节码。文件范围与 SHA-256 见 [源码清单](D:/Project/ai-document-agent/docs/backend-architecture-review-2026-10-19-files.csv)。
- 本次只新增审查文档与清单；没有调整业务实现、依赖、配置、数据库或部署。

| 工作规则 | 判断 |
|---|---|
| Controller 只处理协议、校验和应用服务调用 | 6 个正式业务 Controller 遵循；Rabbit 基准 Controller 是明确例外 |
| 优先复用 Boot / Spring AI 官方抽象 | 核心模型、存储、事务、消息与 WebSocket 已复用；自动配置和部分通用转换仍有改进空间 |

## 1. 正式业务 Controller 的职责符合要求

| Controller | 当前职责 |
|---|---|
| [KnowledgeBaseController](D:/Project/ai-document-agent/src/main/java/com/wxx/aidocumentagent/knowledgebase/api/KnowledgeBaseController.java:42) | HTTP 状态、参数校验、调用 KnowledgeBaseApplicationService |
| [DocumentController](D:/Project/ai-document-agent/src/main/java/com/wxx/aidocumentagent/document/api/DocumentController.java:40) | 将 MultipartFile 转成 UploadDocumentCommand；调用应用服务 |
| [DocumentIngestionController](D:/Project/ai-document-agent/src/main/java/com/wxx/aidocumentagent/ingestion/api/DocumentIngestionController.java:26) | 摄取重试入口；状态规则由应用服务处理 |
| [KnowledgeBaseQueryController](D:/Project/ai-document-agent/src/main/java/com/wxx/aidocumentagent/rag/api/KnowledgeBaseQueryController.java:32) | 将请求转成 RagQueryCommand；调用问答服务 |
| [ConversationController](D:/Project/ai-document-agent/src/main/java/com/wxx/aidocumentagent/conversation/api/ConversationController.java:46) | 会话协议、输入校验、命令转换与服务调用 |
| [ExecutionController](D:/Project/ai-document-agent/src/main/java/com/wxx/aidocumentagent/agent/execution/api/ExecutionController.java:32) | 幂等请求头适配、HTTP 状态与执行应用服务调用 |

这些正式 Controller 没有直接注入 Repository、JdbcTemplate、ChatModel 或 VectorStore，也没有自行决定重试、检索、回答或状态迁移规则。

业务规则有明确归属，例如：

- 知识库重名与非空删除限制位于 [KnowledgeBaseApplicationService](D:/Project/ai-document-agent/src/main/java/com/wxx/aidocumentagent/knowledgebase/application/KnowledgeBaseApplicationService.java:81)。
- 文件类型、签名和大小检查位于 [DocumentUploadValidator](D:/Project/ai-document-agent/src/main/java/com/wxx/aidocumentagent/document/application/DocumentUploadValidator.java:39)，查重与生命周期编排位于 [DocumentApplicationService](D:/Project/ai-document-agent/src/main/java/com/wxx/aidocumentagent/document/application/DocumentApplicationService.java:101)。
- 摄取重试、Outbox、分批索引与恢复在 ingestion.application 中实现。
- 会话两条消息的原子持久化与序号分配位于 [ConversationTurnPersistenceService](D:/Project/ai-document-agent/src/main/java/com/wxx/aidocumentagent/conversation/application/ConversationTurnPersistenceService.java:75)。
- 执行领取、租约、取消与检查点由 ExecutionStore 和 AgentExecution 管理。

`GlobalExceptionHandler`、HTTP 响应包络、分页边界、MultipartFile 转换都是协议适配职责，不需要为了“薄 Controller”继续拆出服务。

## 2. 优先调整的明确偏差

### A. 客户端和向量库手动装配时，遗漏了自动配置提供的能力

涉及三个位置：

1. [RagConfiguration.java:63](D:/Project/ai-document-agent/src/main/java/com/wxx/aidocumentagent/rag/RagConfiguration.java:63) 使用 `ChatClient.builder(chatModel)`，没有使用自动配置的 `ChatClient.Builder`。
2. [VectorConfiguration.java:45](D:/Project/ai-document-agent/src/main/java/com/wxx/aidocumentagent/vector/VectorConfiguration.java:45) 手动构建 QdrantVectorStore，未传入 ObservationRegistry。
3. [HttpCrossEncoderReranker.java:266](D:/Project/ai-document-agent/src/main/java/com/wxx/aidocumentagent/retrieval/rerank/HttpCrossEncoderReranker.java:266) 内部创建请求工厂及 `RestClient.builder()`，未使用 Boot 的 RestClient.Builder。

影响：ChatClient 的自动定制器和 client/advisor 级观测不会自动接入；手动 VectorStore 的观测使用默认 NOOP；重排 HTTP 客户端绕过 Boot 的客户端定制、消息转换和统一观测接入。**这不等于底层 ChatModel / EmbeddingModel 的全部指标也被关闭。**

已用本地 Spring AI 2.0.1 JAR 的 `javap -c` 确认：`ChatClient.builder(ChatModel)` 和 `AbstractVectorStoreBuilder` 默认使用 `ObservationRegistry.NOOP`。

建议：ChatClient 与 RestClient 优先注入官方 Builder 后再追加本项目选项；Qdrant 的 collection 兼容性预检可以保留，同时显式接入 ObservationRegistry、观测约定和必要的批处理定制。无需为了使用自动配置而删掉预检。

依据：[Spring AI ChatClient 自动配置说明](https://docs.spring.io/spring-ai/reference/api/chatclient.html)、[Spring Boot RestClient 说明](https://docs.spring.io/spring-boot/reference/io/rest-client.html)、[Spring AI VectorStore Builder API](https://docs.spring.io/spring-ai/docs/current/api/org/springframework/ai/vectorstore/AbstractVectorStoreBuilder.html)。

### B. Rabbit 监听器工厂跳过了 Boot Configurer

[IngestionConfiguration.java:171](D:/Project/ai-document-agent/src/main/java/com/wxx/aidocumentagent/ingestion/IngestionConfiguration.java:171) 直接创建 SimpleRabbitListenerContainerFactory，仅设置连接工厂、ACK、拒绝重排队、prefetch 和并发数。

影响：Boot 通过 Configurer 提供的 listener 配置、转换器及相关定制不会完整继承。后续配置 `spring.rabbitmq.listener.simple.*` 时，容易出现配置存在、实际工厂未应用的情况。当前代码明确设置的摄取参数仍然会生效。

建议：注入 `SimpleRabbitListenerContainerFactoryConfigurer`，先调用 `configure(factory, connectionFactory)`，再覆盖摄取场景需要的 ACK、并发和 prefetch。应用层的 Outbox 和有限重试策略保留，避免额外打开容器重试后叠加重试次数。

[Boot 官方文档](https://docs.spring.io/spring-boot/reference/messaging/amqp.html) 对自定义监听器工厂提供的正是这一接入方式。

### C. RabbitBenchmarkController 承担了应用服务职责

[依赖与状态](D:/Project/ai-document-agent/src/main/java/com/wxx/aidocumentagent/ingestion/benchmark/RabbitBenchmarkController.java:25) 包含 Repository、JdbcTemplate、轮次作用域 Map 和 ReentrantLock。

[submit 方法](D:/Project/ai-document-agent/src/main/java/com/wxx/aidocumentagent/ingestion/benchmark/RabbitBenchmarkController.java:53) 决定基准轮次知识库的创建与串行执行；sample/export 方法还承担 SQL 聚合、逐条索引一致性校验和报告数据编排。这些超出了 HTTP 适配职责。

建议：将上述职责移入 BenchmarkApplicationService；Controller 只映射输入、调用服务并返回结果。锁、轮次状态和数据库查询由应用服务或其协作者持有。

范围限定：该 Controller 受 [app.benchmark.rabbitmq.node=true](D:/Project/ai-document-agent/src/main/java/com/wxx/aidocumentagent/ingestion/benchmark/RabbitBenchmarkController.java:22) 控制，默认正式应用不会启用它。属于基准代码的规则偏离，不能据此认定正式业务 Controller 普遍越层。

## 3. 有改进空间，但不作为必须重写的违规项

### 应用层仍依赖 HTTP DTO 和 web 包

[KnowledgeBaseApplicationService.java:37](D:/Project/ai-document-agent/src/main/java/com/wxx/aidocumentagent/knowledgebase/application/KnowledgeBaseApplicationService.java:37) 直接接收 Create/UpdateKnowledgeBaseRequest；[ExecutionApplicationService.java:54](D:/Project/ai-document-agent/src/main/java/com/wxx/aidocumentagent/agent/execution/application/ExecutionApplicationService.java:54) 接收 CreateExecutionRequest；多个应用服务直接返回 api.dto。

[RagQueryApplicationService.java:181](D:/Project/ai-document-agent/src/main/java/com/wxx/aidocumentagent/rag/application/RagQueryApplicationService.java:181) 从 TraceIdFilter 获取追踪 ID，也形成了应用层到 web 包的依赖。

这些没有把业务塞进 Controller，**不能单独认定违反第一条规则**；但应用服务迁移到消息、CLI 或其他入口时会带上 HTTP 模块依赖。后续新增或修改用例时，可逐步统一 application Command/Result，并通过现有执行上下文或调用参数传入 traceId，不必一次增加大量映射类。

部分承担状态机行为的 JPA 实体位于 infrastructure.persistence 包。它们从职责上也承担领域模型角色；若团队还要求包目录体现严格领域边界，可以再调整归属。现有两条规则并不要求为每个实体复制一套 PO/DO。

### 工具执行存在一部分可复用的通用机制

[ToolRegistry](D:/Project/ai-document-agent/src/main/java/com/wxx/aidocumentagent/agent/react/ToolRegistry.java:46) 已使用 ToolCallback、ToolDefinition 和官方 JsonSchemaGenerator，这是符合方向的。但 advertised callback 的 call 方法只抛异常，实际名称路由、JSON 绑定与执行走自定义 AgentTool/Registry。

可以逐步评估：以真正可执行的 ToolCallback / FunctionToolCallback 统一工具定义和调用入口，使用官方 ToolCallingManager 或其扩展点承担通用调用机制；本项目继续提供只读限制、参数校验、知识库范围、预算、检查点和安全摘要。

这是一项有条件的收敛建议，不能直接把整个 ReAct 循环替换掉。Spring AI 2.0.1 明确支持 [用户控制的工具执行](https://docs.spring.io/spring-ai/reference/api/tools.html)，同时也说明完全自行驱动循环需要自行承担观测和组合职责。

### 结构化输出的通用 JSON 转换可以进一步复用

[Planner 的 JSON 解析](D:/Project/ai-document-agent/src/main/java/com/wxx/aidocumentagent/agent/plan/Planner.java:105)、ClaimEvidenceValidator 和 ClaimSupportVerifier 中，结构绑定与业务校验有交织。

可以评估用 [BeanOutputConverter / StructuredOutputConverter](https://docs.spring.io/spring-ai/reference/api/structured-output/converters.html) 处理结构绑定，继续保留字段白名单、长度限制、计划 DAG 校验、来源范围、原文 span 和事实约束。转换器不替代业务规则，也不应暗中增加模型重试而突破现有调用预算。

### token 的两种估算口径需要明确管理

[TextUnitRanges.java:36](D:/Project/ai-document-agent/src/main/java/com/wxx/aidocumentagent/chunking/TextUnitRanges.java:36) 用 CJK 字符及连续拉丁字母/数字串估算 token；[ChunkingSupport.java:151](D:/Project/ai-document-agent/src/main/java/com/wxx/aidocumentagent/chunking/ChunkingSupport.java:151) 明确写入 `heuristic-v1`。RAG 与会话记忆则使用官方 JTokkitTokenCountEstimator。

这是代码注释已经说明的确定性切分策略，**不是未说明的模型 tokenizer 实现，也不能直接认定为错误**。切分 tokenCount 不应被当作模型输入的硬上限。若后续希望统一模型预算，可注入官方 TokenCountEstimator，保留页码/偏移映射算法；若继续沿用启发式单位，则应保持名称、文档和使用边界清楚。改变策略需要版本化、重新切分和重新评估基准标签。

## 4. 应保留的业务定制

| 现有定制 | 保留理由 |
|---|---|
| HybridRetriever / RRF 与降级策略 | 组合向量与 BM25，带知识库边界、通道故障和稳定排序；底层仍使用官方 VectorStore / ElasticsearchClient |
| PDF/DOCX/TXT 解析与 OCR | 输出页码、章节、表格、原文偏移与识别质量信息，服务于引用定位；简单替换 reader 可能损失这些约束 |
| 引用与原文核验 | CitationValidator、ClaimEvidenceValidator 处理本产品的来源和事实规则，不能由普通 JSON 转换器替代 |
| 会话持久记录与 Redis 投影 | 数据库是完整会话记录，Redis 保存带消息水位和 TTL 的可重建上下文；消息对原子提交、提交后刷新属于业务一致性 |
| 执行检查点、租约与取消 | ExecutionStore / AgentExecution 处理持久执行状态、所有权和恢复，普通 ChatClient 不提供等价的持久执行语义 |
| Outbox 与摄取状态机 | 处理文件、数据库任务与消息确认之间的一致性，使用 Spring 事务、Spring AMQP 和 Spring 调度扩展业务流程 |
| WebSocket 进度与订阅范围 | 已基于 Spring WebSocket、ConcurrentWebSocketSessionDecorator 和 ThreadPoolTaskExecutor，定制部分是业务事件及订阅语义 |

尤其不要把完整会话历史直接换成默认 MessageWindowChatMemory。官方也区分 [Chat Memory 与 Chat History](https://docs.spring.io/spring-ai/reference/api/chat-memory.html)，并建议完整历史使用 Spring Data 等持久化方式。

QdrantVectorIndex / VectorIndex 这类很薄的业务端口，并不等于自建向量数据库框架；ClassifyingEmbeddingModel 也仍实现官方 EmbeddingModel。判断应看职责和额外复杂度，而不是看是否存在自定义接口。

## 5. 验收与建议顺序

本次以 Java **21.0.12** 执行：

```powershell
.\mvnw.cmd -o '-Dtest=KnowledgeBaseControllerTest,DocumentControllerTest,ConversationControllerTest,KnowledgeBaseQueryControllerTest,ExecutionControllerTest,RagConfigurationOptionsTest,SpringAiAgentModelOptionsTest' test
```

结果：**26 tests，0 failures，0 errors，0 skipped，BUILD SUCCESS**。

| 测试类 | 用例数 |
|---|---:|
| KnowledgeBaseControllerTest | 9 |
| DocumentControllerTest | 5 |
| ConversationControllerTest | 5 |
| KnowledgeBaseQueryControllerTest | 2 |
| ExecutionControllerTest | 2 |
| RagConfigurationOptionsTest | 1 |
| SpringAiAgentModelOptionsTest | 2 |

首次沙箱运行因 Mockito 自附加的 Windows 命名管道权限被阻止而终止；所需权限下重跑同一命令后通过。这是执行环境问题，未把首次运行计为代码测试失败或通过。

测试使用本地 Mock，没有调用真实模型或连接真实数据库。未运行外部服务集成、故障注入和性能基准；上述测试也不证明自动配置问题已经修复。正式摄取重试 Controller 本次完成静态检查，没有单独的同名 ControllerTest 被这条命令执行。

建议先修复客户端/VectorStore 观测与 Builder 接入、Rabbit Configurer 接入，再将 RabbitBenchmarkController 的应用逻辑移出。协议 DTO 依赖、工具机制、结构化转换和 token 策略作为后续逐项评估的改进，保留现有业务一致性与证据约束。


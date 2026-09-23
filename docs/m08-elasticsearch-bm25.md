# M08 Elasticsearch BM25 全文索引

## 索引与部署

- 版本化物理索引：`document_chunks_v1`。
- 读写别名：`document_chunks`；所有 bulk 写入要求该别名存在，避免误写物理索引。
- mapping 由 `DocumentChunksIndexInitializer` 在应用启动时创建，字段为 `chunkId`、`knowledgeBaseId`、`documentId`、`content`、`title`、`sectionTitle`、`pageFrom`、`pageTo` 和 `contentHash`。
- `content`、`title`、`sectionTitle` 显式使用 BM25；Compose 固定使用 `docker.elastic.co/elasticsearch/elasticsearch:9.4.5`，与项目当前 Elasticsearch Java Client `9.4.5` 对齐。
- 本地 Compose 启用 Elasticsearch Basic Auth：内置用户为 `elastic`，口令仅由被 Git 忽略的 `.env` 注入；HTTP TLS 仅为本地开发关闭，生产环境必须使用 HTTPS 与密钥管理。配置完整 Basic 凭据时优先使用它；仅未配置完整 Basic 凭据时才使用 API Key。

## 中文与编号策略

不安装 IK 或其他镜像外插件。主字段使用 Elasticsearch 官方内置 `standard` 分析器，以保留英文术语、编号和短语；`ngram` 子字段使用内置 2~3 字符 n-gram tokenizer，补足中文关键词命中。查询同时覆盖两组字段并提高标题、章节标题权重。

这不是词典级中文分词：单字查询和跨长词边界的召回能力有限，n-gram 还会增加索引体积。若后续需要行业词典、同义词或更高中文检索质量，应以新物理索引版本（例如 `document_chunks_v2`）迁移，而不是原地修改 v1 mapping。

## 一致性与重试

每个 `DocumentBatchTask` 依次运行 `VECTOR_INDEX`、`KEYWORD_INDEX`。vector 成功后，同一事务将 task 切换为关键词阶段并写入 stage 固化的 outbox 事件；仅关键词阶段成功才将 batch 聚合为完成。任务行以 `batchId + 当前 stage` 标识，配合 JPA version 和行锁保证状态机串行；ES `_id=chunkId` 使崩溃重放和重复消费成为覆盖写。

Elasticsearch 超时、暂时不可用和 429 会走已有有限重试；鉴权、mapping/bulk 参数等永久错误会终止任务。重处理和文档删除均以 `knowledgeBaseId + documentId` 删除旧 ES 文档。

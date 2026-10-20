# 公开数据集评测

直接复用上游问题、接受答案和来源标注，不生成新题或改写参考答案。

| 数据 | 原始公开划分 | 固定抽样 | 检索语料 |
|---|---|---:|---|
| [CMRC 2018](https://github.com/ymcui/cmrc2018) | dev，3,219 题 | 200 题 | dev 全部 848 篇原文 |
| [HotpotQA](https://hotpotqa.github.io/) | distractor validation，7,405 题 | 100 题 | 所选题全部原文及干扰段落的并集，988 篇 |

两个数据集单独建立知识库，在隔离的 `benchmark_public` 向量 collection 与 ES index 中检索。抽样规则为按 `SHA256(20261019|dataset:original_id)` 排序取前 N，选择发生在模型运行前。原始文件、派生文件和 manifest 分别保存 SHA-256。

数据为 CC BY-SA 4.0，作者、论文、来源、许可与格式适配说明见 [ATTRIBUTION.md](ATTRIBUTION.md)。HotpotQA 原 CMU 下载服务超时后，采用 `hotpotqa/hotpot_qa` Hugging Face Parquet 发布，核验公布的 SHA-256；没有使用第三方生成的答案。PyArrow 仅安装在本目录的 `runtime/python-libs`。

上游也存在标注边界：CMRC 有 193 个 `answer_start=-1` 的答案别名，3 个问题没有可定位的答案跨度。所有官方接受答案保留用于 EM，只有与原文精确一致的合法跨度用于 chunk 标签。HotpotQA 全部公开验证题中发现 1 题的支撑句索引越界，本次固定抽样 100 题没有该问题。诊断保存于 manifest，不通过替换题目规避。

## 准备及检查

仓库根目录执行，Python 3.12、Java 21。可使用 Codex 提供的 Python 运行时或自己的 Python：

```powershell
python -m pip install --no-cache-dir --target benchmarks/public/runtime/python-libs -r benchmarks/public/requirements.txt
python benchmarks/public/prepare.py prepare
python benchmarks/public/prepare.py validate
python benchmarks/public/preflight.py
python -m unittest discover -s benchmarks/public -p 'test_*.py' -v
```

`raw/` 保留下载文件、下载 URL 和摘要；`prepared/cmrc200-hotpot100-seed20261019/` 包含：

- `corpus.jsonl`：原文、标题、上游 ID、内容摘要。
- `qa.jsonl`：原始问题、完整接受答案列表、原文证据与题型信息。
- `retrieval.jsonl`：问题、相关原始文档与证据位置。
- `agent-tasks.jsonl`：100 个原始多跳问题，三种 Agent 分别执行同样问题，共 300 次。
- `manifest.json` / `sources.lock.json`：固定样本、原始文件和派生文件摘要、来源与边界。

准备命令拒绝覆盖现有目录。变更抽样数量或 seed 时指定新的 `--output`。公开集没有拒答或多轮标签，不人为补造这类标签。

## 接入实际项目

```powershell
.\scripts\run-public-benchmark.ps1 -Mode Seed -UseHttpsProxy
.\scripts\run-public-benchmark.ps1 -Mode Retrieval -UseHttpsProxy -Concurrency 4
.\scripts\run-public-benchmark.ps1 -Mode Latency -UseHttpsProxy -Concurrency 10
.\scripts\run-public-benchmark.ps1 -Mode Qa -UseHttpsProxy -Concurrency 2
.\scripts\run-public-benchmark.ps1 -Mode Agent -UseHttpsProxy -Concurrency 2
```

不需要代理时省略 `-UseHttpsProxy`。默认使用真实 DashScope `text-embedding-v4` 和 `gte-rerank-v2`，Chat 使用现有配置（当前为 DeepSeek Flash）。供应商、实际模型与运行参数进入报告；API key 从已有配置读取，不写入脚本、参数文件或报告。`-RerankProvider Configured` 使用项目配置的重排供应商。`-Rerank $false` 是独立的无重排实验。

Seed 复用生产切分策略、真实 MySQL 持久化、真实索引端口和原始文件存储，写入本次创建的独立知识库与隔离索引。这个显式语料准备过程不经过上传、Outbox、OCR，不用于计算上传等待或摄取吞吐。文档只在真实索引写入完成后置为 READY。操作不删除原知识库、原索引或已有报告。`binding.json` 绑定实际文档/chunk ID、内容摘要和切分配置。

基准知识库供独立 Java 评测进程使用：主 Web 应用仍查询自己的生产索引，本次基准原件保存在本地 `prepared/.../storage`，没有挂载到 Web 后端容器。这套准备过程不代表 Web 上传、预览或默认索引查询的端到端验收。

适配器单独编译到 `runtime/classes`，没有修改生产应用的默认 Bean 或现有 benchmark 判定规则。Embedding 显式复用已有 benchmark 的 JDK HTTP/1.1 实现；这是报告中声明的传输环境。

先检查链路可使用 `-LimitPerDataset 1`。这只产生冒烟报告；完整正式评测省略该参数。每条正式结果保留，连续依赖错误触发停止时剩余条目标注 NOT_RUN，完整分母保留。

因依赖故障导致未执行的样本使最终质量指标显示 N/A，同时报告已确认结果的上下界；执行失败与未执行分别计数。评分脚本 v2 修正了早期调试汇总把未执行作为 0 分显示的问题，原始观察没有改变，最终结果以 v2 汇总为准。质量指标恢复后应生成新的完整运行，不拼接多次不完整运行来宣称达标。

## 评分

```powershell
python benchmarks/public/evaluate.py reports/public-benchmark/retrieval-实际运行.json reports/public-benchmark/latency-实际运行.json reports/public-benchmark/qa-实际运行.json reports/public-benchmark/agent-实际运行.json
```

- `documentRecallAt5`：前 5 个 chunk 覆盖的相关原始文档数 / 该题相关原始文档总数。
- `documentHitAt5`：前 5 个 chunk 是否命中至少一个相关原始文档。
- `allGoldDocumentsAt5`：是否覆盖全部相关原始文档，多跳题通常需要两篇。
- `chunkHitAt5`：前 5 个 chunk 是否命中能包含上游证据原文的 chunk。没有可映射跨度的题单列，报告该项分母。
- 问答 `exactMatch`：仅移除引用标记后，按上游规范化规则与原始接受答案比较。CMRC v5 EM 转为 Python 3；HotpotQA 另计算官方答案 token F1。不使用模型从长答案中提取短答案，也不使用子串命中代替正确。
- `sourceCitedExactMatch`：答案 EM 正确、引用 ID 有效且引用了全部标准来源文档。
- Agent `taskPassed`：在上述问答与引用条件上，要求任务实际 COMPLETED。它评估单轮公开问答任务，不能代替多轮、工具失败、重规划的完整业务任务验收。
- P95：至少 50 次顺序预热，至少 500 次正式请求，并发明确记录。计时覆盖 embedding、并行双路检索、RRF 和重排，不含最终答案生成。错误、降级和失败预热会使 490ms 目标不可判定。

自动公开基准 EM 与现有 M19B 的人工语义 QA Accuracy 分开报告；公开 QA 任务成功率与 M19C 的完整业务 Agent TaskSuccessRate 分开报告。公开开发集改编子集的结果不等同于官方排行榜成绩。

## AI 逐题语义复核

当前完整运行的 300 个 QA 输出已由 AI 助手逐题阅读问题、原始接受答案、来源和实际可见上下文后复核，见 [复核报告](../../reports/public-benchmark/semantic-review-20261019T195208569072Z/review.md)及[全部判定理由](../../reports/public-benchmark/semantic-review-20261019T195208569072Z/cases.md)。这次复核不包含三种 Agent 的另外 300 个输出。

判定为正确、部分正确、错误、拒答、争议五类。等价名称、同义表达和合理改写可以判正确；遗漏必要事实、答非所问、错误实体和无依据实质补充不能因包含标准答案就判正确。只有完整正确计入分子，所有 300 题均保留在分母，争议不计为已确认正确。实际上下文支持和原有引用 ID/来源覆盖检查分别记录；允许最小充分或替代证据，不将缺少全部标注来源直接等同于答案无依据。

事前规则、六批逐题决定、两项有理由的一致性修订和输入摘要均保留。结果为 **266/300=88.67%**，其中 CMRC **195/200=97.5%**、HotpotQA **71/100=71%**；部分正确 5、错误 7、拒答 13、争议 9。实际可见上下文充分支持的语义正确答案为 **262/300=87.33%**。原始接受答案、预测和 EM 均未改写，也未重新调用被测模型。

`summarize_semantic_review.py` 只审核显式判定记录并复算统计，**不会自动完成语义判断**。以下命令核验现有交付与输入、逐题记录和算术是否一致：

```powershell
python benchmarks/public/summarize_semantic_review.py --review-dir reports/public-benchmark/semantic-review-20261019T195208569072Z --verify-only
```

完整性核验不证明每项语义判断必然正确。以上是公开集 AI 语义复核，独立于原 EM 和 M19B 业务集人工最终验收，不据此认证业务目标 83%。

会话丢失率、上传 ACK 等待和摄取吞吐继续使用 `benchmarks/recovery`、`benchmarks/rabbitmq` 中的独立测试，不从问答数据推算。

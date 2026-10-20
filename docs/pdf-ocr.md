# PDF 自动中文 OCR

PDF 上传后先用 PDFBox 提取文字。遇到乱码文字层、扫描图片页或仅剩简短页脚的扫描页，自动逐页渲染并调用本地 Tesseract；正常文字页保留原有抽取结果。识别结果经文字质量检查后进入原来的切块、向量/关键词索引及问答流程，保留原 PDF 页码与文字区间。

OCR 只在本机运行，不调用外部识别 API，也不需要新增 API Key。Docker 镜像已包含 Tesseract、简体中文/英文语言包和 CJK 字体。手动启动 Java 时需自行安装对应引擎/语言包后设置 `DOCUMENT_OCR_ENABLED=true`；可通过 `DOCUMENT_OCR_COMMAND` 指定可执行文件的绝对路径。

## 这本《红楼梦》的操作

在项目目录执行以下命令，重建后端并应用长文档的配套超时配置：

```powershell
Set-Location D:\Project\ai-document-agent
docker compose -f compose.yaml -f compose.ocr-long-documents.yaml up -d --build
```

命令会按配置重建/重启本项目相关服务，保留已有数据库及文件卷。请等现有上传/问答任务完成后再执行。没有执行过 Docker 初始化时，先按原部署文档准备 `.env` 和 secrets。

随后在页面中：

1. 新建「红楼梦-OCR验证」知识库。
2. 上传原来的 PDF。无需事先手动 OCR，也无需转成 TXT。
3. 等待解析、OCR 和索引流程结束，状态变为 `READY`。整本书是长任务，三页抽样耗时不能作为全书完成时间的保证。
4. 新建会话，先问「贾宝玉的父母是谁？请引用原文」。检查答案及引用是否匹配原书，再检查解释性问题。
5. 原知识库暂时保留；确认新版本可用后再决定是否清理旧乱码文档。

同一知识库会按文件内容去重，所以直接把同一个 PDF 再传到旧库会得到重复文档提示。当前 `READY` 文档没有手动重新解析入口；使用新知识库可保留原文和历史问答记录。

## 普通文档启动

少量页面可使用原启动方式：

```powershell
docker compose up -d --build backend
```

普通配置整篇处理时限为 25 分钟。1162 页的书不应使用这一时限直接重跑。长文档覆盖文件将整篇 OCR 上限设为 6 小时、摄取超时恢复阈值设为 7 小时、RabbitMQ 消费确认超时设为 8 小时，并采用单消费者/单预取，避免长任务等待期间消息提前超时。6 小时是处理上限，不是预计耗时。

RabbitMQ 消费确认时限必须大于文档处理时限，参见 [RabbitMQ 4.1 消费者文档](https://www.rabbitmq.com/docs/4.1/consumers#acknowledgement-timeout)。环境配置方式参见 [RabbitMQ 配置说明](https://www.rabbitmq.com/docs/configure)。

## 配置与失败处理

| 环境变量 | 普通默认值 | 作用 |
| --- | --- | --- |
| DOCUMENT_OCR_ENABLED | Docker true；直接 Java false | 启用自动 OCR 回退 |
| DOCUMENT_OCR_COMMAND | tesseract | 本地可执行文件，直接传参，不经 shell |
| DOCUMENT_OCR_LANGUAGE | chi_sim+eng | 简体中文和英文，其他语言须安装对应包 |
| DOCUMENT_OCR_DPI | 300 | 页面渲染分辨率 |
| DOCUMENT_OCR_PAGE_TIMEOUT | 60s | 单页排队、渲染和识别总时限 |
| DOCUMENT_OCR_DOCUMENT_TIMEOUT | 25m | 整篇解析/OCR时限；长文档配置覆盖为 6h |
| DOCUMENT_OCR_MAX_CONCURRENT_PAGES | 1 | 同一后端进程同时渲染/识别的页面上限 |
| DOCUMENT_OCR_MAX_PAGES | 2000 | 单个 PDF 可执行 OCR 的页数上限 |
| DOCUMENT_OCR_MAX_RENDERED_PIXELS | 16000000 | 单页渲染像素上限 |

单页识别结果额外限制为 1 MiB。缺少引擎、缺少语言包、识别超时、页面超限会返回明确错误，沿现有摄取失败流程标为 `FAILED`，不自动重复整本书的耗时识别。修复配置后可在失败文档详情点击「重新处理」。真正的空白页不会启动 OCR；仍然损坏的识别输出不会入库。

文字层损坏而 OCR 返回空文本时，也必须失败，不能仅凭其他页面有文字就把该页丢失的文档标为 READY。图片页 OCR 没有识别到文字时保留空页告警。

OCR 存在错字、标点和正文/夹批顺序误差。`READY` 表示处理与索引流程完成，不表示全书 OCR 已经人工校对；用于文学解释和精确引用前应抽查相关原页。中文识别参数参见 [Tesseract 官方命令行文档](https://tesseract-ocr.github.io/tessdoc/Command-Line-Usage.html)。

## 本次验证范围

已用原书第 30、50、100 页在断网独立容器中验证实际解析器和中文 OCR 回退，三页均恢复出可读汉字，检查了页面文字区间；原文页码与抽样页码的对应关系单独记录。识别结果仍存在错字，本次没有声称字符准确率、答案准确率或全书验收通过。

没有对运行中的服务实施本次部署，没有替换旧知识库，也没有执行 1162 页全书 OCR/重新入库。测试详细记录见 `target/ocr-diagnostic/`。

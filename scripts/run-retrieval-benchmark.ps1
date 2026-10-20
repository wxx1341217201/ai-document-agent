[CmdletBinding()]
param(
    [ValidateSet('Live', 'DashScope', 'Local', 'Fake', 'Validate')][string]$Mode = 'Live',
    [string]$Dataset = 'benchmarks/retrieval/retrieval-dataset.jsonl',
    [string]$Corpus = '',
    [string]$SupplementalCorpus = 'benchmarks/retrieval/supplemental-corpus.jsonl',
    [string]$ReportDirectory = 'reports',
    [string]$LabelReview = '',
    [switch]$Bootstrap,
    [string]$Provider = '',
    [string]$Region = '',
    [string]$EmbeddingProvider = 'Alibaba Cloud DashScope',
    [string]$EmbeddingRegion = 'cn-beijing; dashscope.aliyuncs.com',
    [string]$Deployment = 'Windows localhost; Qdrant and Elasticsearch in Docker',
    [string]$VectorCollection = 'ai_document_chunks_benchmark_m19a_v3',
    [string]$KeywordIndex = 'document_chunks_benchmark_m19a_v3',
    [string]$KeywordAlias = 'document_chunks_benchmark_m19a_v3_alias',
    [ValidateRange(1, 100)][int]$VectorTopK = 20,
    [ValidateRange(1, 100)][int]$KeywordTopK = 20,
    [ValidateRange(1, 1000)][int]$RrfK = 60,
    [Alias('RerankTopN')][ValidateRange(5, 100)][int]$RerankCandidates = 50,
    [ValidateRange(50, 100000)][int]$Warmup = 50,
    [ValidateRange(500, 100000)][int]$Samples = 500,
    [ValidateRange(1, 100)][int]$Concurrency = 10,
    [ValidateRange(1, 100)][int]$QualityConcurrency = 1,
    [ValidateRange(1, 65535)][int]$ModelPort = 8098,
    [string]$PythonPath = '',
    [ValidateRange(32, 512)][int]$ModelMaxLength = 256,
    [ValidateRange(1, 32)][int]$ModelThreads = 2,
    [ValidateRange(1, 32)][int]$ModelParallelism = 2,
    [switch]$UseHttpsProxy,
    [switch]$SkipCompile
)
$ErrorActionPreference = 'Stop'
$taskRepo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$taskJava = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java -ErrorAction Stop).Source }
$taskHelper = $null
if ($Mode -eq 'Live' -and ([string]::IsNullOrWhiteSpace($Provider) -or [string]::IsNullOrWhiteSpace($Region))) {
    throw 'Live requires -Provider and -Region, including an explicit unknown-serving-region note if the supplier does not disclose it.'
}
function ConvertTo-JavaArgument([string]$Value) { '"' + $Value.Replace('\', '\\').Replace('"', '\"') + '"' }
Push-Location $taskRepo
try {
    $taskRuntime = Join-Path $taskRepo 'target/retrieval-benchmark'
    New-Item -ItemType Directory -Path $taskRuntime -Force | Out-Null
    if (-not $SkipCompile) {
        & .\mvnw.cmd '-Dmaven.compiler.fork=true' -DskipTests compile
        if ($LASTEXITCODE -ne 0) { throw "Compilation failed: exit $LASTEXITCODE" }
    }
    $taskClasspathFile = Join-Path $taskRuntime 'classpath.txt'
    if (-not $SkipCompile -or -not (Test-Path -LiteralPath $taskClasspathFile)) {
        & .\mvnw.cmd -q dependency:build-classpath "-Dmdep.outputFile=$taskClasspathFile"
        if ($LASTEXITCODE -ne 0) { throw "Classpath generation failed: exit $LASTEXITCODE" }
    }
    $taskClasspath = (Join-Path $taskRepo 'target/classes') + ';' + (Get-Content -LiteralPath $taskClasspathFile -Raw).Trim()
    $taskArguments = @('-Xms128m', '-Xmx768m')
    if ($UseHttpsProxy) {
        if (-not $env:HTTPS_PROXY) { throw 'UseHttpsProxy requires HTTPS_PROXY in this process environment.' }
        $taskProxy = [Uri]$env:HTTPS_PROXY
        $taskArguments += @("-Dhttps.proxyHost=$($taskProxy.Host)", "-Dhttps.proxyPort=$($taskProxy.Port)")
        $Deployment += "; HTTPS proxy $($taskProxy.Host):$($taskProxy.Port)"
    }
    $taskArguments += @('-cp', $taskClasspath, 'com.wxx.aidocumentagent.retrieval.benchmark.RetrievalBenchmarkRunner')
    if ($Mode -eq 'Validate') {
        $taskArguments += @('--validate-only', "--dataset=$Dataset")
        if ($Corpus) { $taskArguments += "--corpus=$Corpus" }
    }
    else {
        $taskProfile = 'EXTERNAL'
        if ($Mode -eq 'DashScope') {
            $taskArguments += @('--app.rerank.enabled=true', '--app.rerank.provider=dashscope',
                '--app.rerank.url=https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank',
                '--app.rerank.model=gte-rerank-v2', '--app.rerank.api-key=${DASHSCOPE_API_KEY}')
            $Provider = 'Alibaba Cloud DashScope; gte-rerank-v2'
            $Region = 'cn-beijing; dashscope.aliyuncs.com'
        }
        if ($Mode -in @('Local', 'Fake')) {
            if (-not $PythonPath) {
                $taskBundledPython = Join-Path $env:USERPROFILE '.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
                $PythonPath = if (Test-Path -LiteralPath $taskBundledPython) { $taskBundledPython } else { (Get-Command python -ErrorAction Stop).Source }
            }
            $taskProfile = if ($Mode -eq 'Local') { 'LOCAL_REAL' } else { 'FAKE_FIXED_LATENCY' }
            $taskPrefix = Join-Path $taskRuntime ('model-' + [guid]::NewGuid().ToString('N'))
            $taskInstance = [guid]::NewGuid().ToString('N')
            $taskStartOptions = @{ FilePath = $PythonPath; ArgumentList = @('benchmarks/retrieval/local_model_server.py',
                '--mode', $taskProfile, '--port', $ModelPort, '--max-length', $ModelMaxLength,
                '--threads', $ModelThreads, '--parallelism', $ModelParallelism, '--instance-id', $taskInstance)
                WorkingDirectory = $taskRepo; WindowStyle = 'Hidden'; RedirectStandardOutput = "$taskPrefix.stdout.log"
                RedirectStandardError = "$taskPrefix.stderr.log"; PassThru = $true }
            $taskHelper = Start-Process @taskStartOptions
            $taskReady = $false
            for ($taskAttempt = 0; $taskAttempt -lt 60; $taskAttempt++) {
                if ($taskHelper.HasExited) { throw "Model helper exited. See $taskPrefix.stderr.log" }
                try {
                    $taskMetadata = Invoke-RestMethod -Uri "http://127.0.0.1:$ModelPort/metadata" -TimeoutSec 2
                    if ($taskMetadata.profile -ne $taskProfile -or $taskMetadata.instanceId -ne $taskInstance) { throw 'Model port belongs to another helper.' }
                    $taskReady = $true
                    break
                } catch { Start-Sleep -Milliseconds 500 }
            }
            if (-not $taskReady) { throw 'Local model helper did not become ready.' }
            $taskMetadata | ConvertTo-Json -Depth 15 | Set-Content -LiteralPath "$taskPrefix.metadata.json" -Encoding utf8
            $taskArguments += @('--app.rerank.enabled=true', '--app.rerank.provider=generic', '--app.rerank.api-key=',
                "--app.rerank.url=http://127.0.0.1:$ModelPort/rerank", "--app.rerank.model=$($taskMetadata.model)",
                "--app.benchmark.retrieval.model-metadata=$taskPrefix.metadata.json")
            $Provider = if ($Mode -eq 'Local') { 'Local ONNX CPU Cross-Encoder' } else { 'FAKE models; regression only' }
            $Region = 'localhost; no remote reranker region'
            if ($Mode -eq 'Fake') {
                $VectorCollection = 'ai_document_chunks_benchmark_m19a_fake'
                $KeywordIndex = 'document_chunks_benchmark_m19a_fake_v1'
                $KeywordAlias = 'document_chunks_benchmark_m19a_fake'
                $EmbeddingProvider = 'FAKE signed token hashing'
                $EmbeddingRegion = 'localhost'
                $taskArguments += @("--spring.ai.openai.embedding.base-url=http://127.0.0.1:$ModelPort",
                    '--spring.ai.openai.embedding.api-key=fake', '--app.vector.embedding.model=fake-token-hash-v1')
            }
        }
        $taskArguments += @("--app.benchmark.retrieval.profile=$taskProfile", "--app.benchmark.retrieval.dataset=$Dataset",
            "--app.benchmark.retrieval.report-directory=$ReportDirectory", "--app.benchmark.retrieval.provider=$Provider",
            "--app.benchmark.retrieval.region=$Region", "--app.benchmark.retrieval.embedding-provider=$EmbeddingProvider",
            "--app.benchmark.retrieval.embedding-region=$EmbeddingRegion", "--app.benchmark.retrieval.deployment=$Deployment",
            "--app.benchmark.retrieval.warmup=$Warmup", "--app.benchmark.retrieval.samples=$Samples",
            "--app.benchmark.retrieval.concurrency=$Concurrency", "--app.benchmark.retrieval.quality-concurrency=$QualityConcurrency",
            "--app.benchmark.retrieval.bootstrap=$($Bootstrap.IsPresent.ToString().ToLowerInvariant())",
            "--app.vector.qdrant.collection-name=$VectorCollection", "--app.keyword.index-name=$KeywordIndex",
            "--app.keyword.alias=$KeywordAlias", "--app.retrieval.vector-top-k=$VectorTopK", "--app.retrieval.keyword-top-k=$KeywordTopK",
            "--app.retrieval.rrf-k=$RrfK", "--app.rerank.max-candidates=$RerankCandidates", '--app.rerank.max-retries=0',
            '--app.rerank.read-timeout=60s', '--app.retrieval.vector-timeout=10s', '--app.retrieval.keyword-timeout=5s',
            '--app.retrieval.total-timeout=12s', '--app.vector.embedding.batch-size=10',
            '--app.vector.embedding.max-retries=0', '--spring.ai.openai.embedding.max-retries=0',
            '--logging.level.com.wxx.aidocumentagent.retrieval.rerank.RerankingApplicationService=WARN')
        if ($Corpus) { $taskArguments += "--app.benchmark.retrieval.corpus=$Corpus" }
        elseif ($SupplementalCorpus) { $taskArguments += "--app.benchmark.retrieval.supplemental-corpus=$SupplementalCorpus" }
        if ($LabelReview) { $taskArguments += "--app.benchmark.retrieval.label-review=$LabelReview" }
    }
    $taskArgsFile = Join-Path $taskRuntime ('run-' + [guid]::NewGuid().ToString('N') + '.args')
    [IO.File]::WriteAllLines($taskArgsFile, [string[]]($taskArguments | ForEach-Object { ConvertTo-JavaArgument $_ }),
        (New-Object Text.UTF8Encoding($false)))
    & $taskJava "@$taskArgsFile"
    if ($LASTEXITCODE -ne 0) { throw "Retrieval benchmark failed: exit $LASTEXITCODE" }
}
finally {
    if ($null -ne $taskHelper -and -not $taskHelper.HasExited) { Stop-Process -Id $taskHelper.Id -Force }
    Pop-Location
}

[CmdletBinding()]
param(
    [ValidateSet('Validate', 'Seed', 'Retrieval', 'Latency', 'Qa', 'Agent')][string]$Mode = 'Validate',
    [string]$Prepared = 'benchmarks/public/prepared/cmrc200-hotpot100-seed20261019',
    [string]$ReportDirectory = 'reports/public-benchmark',
    [ValidateRange(0, 100000)][int]$LimitPerDataset = 0,
    [ValidateRange(1, 20)][int]$Concurrency = 2,
    [ValidateRange(50, 100000)][int]$Warmup = 50,
    [ValidateRange(500, 100000)][int]$Samples = 500,
    [bool]$Rerank = $true,
    [ValidateSet('DashScope', 'Configured')][string]$RerankProvider = 'DashScope',
    [switch]$SkipCompile,
    [switch]$UseHttpsProxy
)
$ErrorActionPreference = 'Stop'
$taskRepo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$taskPython = Join-Path $env:USERPROFILE '.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
if (-not (Test-Path -LiteralPath $taskPython)) { $taskPython = (Get-Command python -ErrorAction Stop).Source }
$taskJava = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java -ErrorAction Stop).Source }
$taskJavac = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/javac.exe' } else { (Get-Command javac -ErrorAction Stop).Source }
$taskPreviousElasticUsername = $env:ELASTICSEARCH_USERNAME
$taskPreviousElasticPassword = $env:ELASTICSEARCH_PASSWORD
function ConvertTo-PublicJavaArgument([string]$Value) { '"' + $Value.Replace('\', '\\').Replace('"', '\"') + '"' }
Push-Location $taskRepo
try {
    & $taskPython benchmarks/public/prepare.py validate --output $Prepared
    if ($LASTEXITCODE -ne 0) { throw 'Public source/label validation failed' }
    if ($Mode -eq 'Validate') { return }
    $taskPreparedPath = (Resolve-Path -LiteralPath $Prepared).Path
    $taskRuntime = Join-Path $taskRepo 'benchmarks/public/runtime'
    $taskClasses = Join-Path $taskRuntime 'classes'
    New-Item -ItemType Directory -Path $taskClasses -Force | Out-Null
    if (-not $SkipCompile) {
        & .\mvnw.cmd '-Dmaven.compiler.fork=true' -DskipTests compile
        if ($LASTEXITCODE -ne 0) { throw 'Application compilation failed' }
    }
    $taskClasspathFile = Join-Path $taskRepo 'target/retrieval-benchmark/classpath.txt'
    if (-not (Test-Path -LiteralPath $taskClasspathFile)) {
        & .\mvnw.cmd -q dependency:build-classpath "-Dmdep.outputFile=$taskClasspathFile"
        if ($LASTEXITCODE -ne 0) { throw 'Classpath generation failed' }
    }
    $taskClasspath = $taskClasses + ';' + (Join-Path $taskRepo 'target/classes') + ';' + (Get-Content -LiteralPath $taskClasspathFile -Raw).Trim()
    $taskSource = Join-Path $taskRepo 'benchmarks/public/java/com/wxx/aidocumentagent/retrieval/benchmark/PublicBenchmarkBridge.java'
    $taskCompileArgs = Join-Path $taskRuntime 'compile.args'
    [IO.File]::WriteAllLines($taskCompileArgs, [string[]](@('-proc:none', '-encoding', 'UTF-8', '-cp', $taskClasspath, '-d', $taskClasses, $taskSource) | ForEach-Object { ConvertTo-PublicJavaArgument $_ }), (New-Object Text.UTF8Encoding($false)))
    & $taskJavac '-J-Duser.language=en' '-J-Duser.country=US' "@$taskCompileArgs"
    if ($LASTEXITCODE -ne 0) { throw 'Public adapter compilation failed' }
    $taskEnvValues = @{}
    foreach ($taskLine in Get-Content -LiteralPath .env) {
        if (-not $taskLine.TrimStart().StartsWith('#') -and $taskLine.Contains('=')) {
            $taskPair = $taskLine.Split('=', 2)
            $taskEnvValues[$taskPair[0].Trim()] = $taskPair[1].Trim().Trim('"').Trim("'")
        }
    }
    # Match the existing Docker service's ES credentials in this child process only.
    if (-not $env:ELASTICSEARCH_USERNAME) { $env:ELASTICSEARCH_USERNAME = 'elastic' }
    if (-not $env:ELASTICSEARCH_PASSWORD -and $taskEnvValues['ELASTIC_PASSWORD']) { $env:ELASTICSEARCH_PASSWORD = $taskEnvValues['ELASTIC_PASSWORD'] }
    $taskFingerprint = (Get-FileHash -LiteralPath (Join-Path $taskPreparedPath 'manifest.json') -Algorithm SHA256).Hash.ToLowerInvariant().Substring(0, 12)
    $taskCollection = 'ai_document_chunks_benchmark_public_' + $taskFingerprint
    $taskIndex = 'document_chunks_benchmark_public_' + $taskFingerprint
    $taskClassRecords = Get-ChildItem -LiteralPath (Join-Path $taskRepo 'target/classes') -Filter '*.class' -File -Recurse | Sort-Object FullName | ForEach-Object {
        $_.FullName.Substring($taskRepo.Length).Replace('\','/') + ':' + (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    $taskClassDigestBytes = [Text.Encoding]::UTF8.GetBytes(($taskClassRecords -join "`n"))
    $taskClassDigest = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($taskClassDigestBytes)).ToLowerInvariant()
    $taskAdapterDigest = (Get-FileHash -LiteralPath $taskSource -Algorithm SHA256).Hash.ToLowerInvariant()
    $taskCodeSnapshots = Join-Path ([IO.Path]::GetFullPath((Join-Path $taskRepo $ReportDirectory))) 'code-snapshots'
    New-Item -ItemType Directory -Path $taskCodeSnapshots -Force | Out-Null
    $taskCodeSnapshot = Join-Path $taskCodeSnapshots ($taskAdapterDigest + '.java')
    if (-not (Test-Path -LiteralPath $taskCodeSnapshot)) { Copy-Item -LiteralPath $taskSource -Destination $taskCodeSnapshot }
    $taskArguments = @('-Xms128m', '-Xmx768m', '-XX:ActiveProcessorCount=4', "-Djava.io.tmpdir=$taskRuntime")
    if ($UseHttpsProxy) {
        if (-not $env:HTTPS_PROXY) { throw 'UseHttpsProxy requires HTTPS_PROXY' }
        $taskProxy = [Uri]$env:HTTPS_PROXY
        $taskArguments += @("-Dhttps.proxyHost=$($taskProxy.Host)", "-Dhttps.proxyPort=$($taskProxy.Port)")
    }
    $taskArguments += @('-cp', $taskClasspath, 'com.wxx.aidocumentagent.retrieval.benchmark.PublicBenchmarkBridge',
        "--public.phase=$($Mode.ToLowerInvariant())", "--public.prepared=$taskPreparedPath", "--public.reports=$ReportDirectory",
        "--public.application-classes-sha256=$taskClassDigest", "--public.adapter-source-sha256=$taskAdapterDigest",
        "--public.limit=$LimitPerDataset", "--public.concurrency=$Concurrency", "--public.warmup=$Warmup", "--public.samples=$Samples",
        "--public.rerank=$($Rerank.ToString().ToLowerInvariant())", "--app.vector.qdrant.collection-name=$taskCollection",
        "--app.keyword.index-name=$taskIndex", "--app.keyword.alias=$($taskIndex)_alias",
        "--app.vector.qdrant.initialization-strategy=$(if ($Mode -eq 'Seed') { 'CREATE_IF_MISSING' } else { 'VALIDATE_EXISTING' })",
        "--app.keyword.initialize-schema=$((($Mode -eq 'Seed')).ToString().ToLowerInvariant())",
        "--app.document.storage-root=$(Join-Path $taskPreparedPath 'storage')",
        '--app.retrieval.vector-top-k=40', '--app.retrieval.keyword-top-k=40', '--app.retrieval.rrf-k=60',
        '--app.retrieval.vector-timeout=20s', '--app.retrieval.keyword-timeout=5s', '--app.retrieval.total-timeout=25s',
        '--app.vector.embedding.batch-size=10', '--app.vector.embedding.max-retries=0', '--spring.ai.openai.embedding.max-retries=0',
        '--app.rerank.max-retries=0', '--app.rerank.max-candidates=50', '--app.rerank.read-timeout=30s',
        '--spring.ai.openai.chat.temperature=0.0', '--logging.level.root=WARN')
    if ($RerankProvider -eq 'DashScope') {
        $taskArguments += @("--app.rerank.enabled=$($Rerank.ToString().ToLowerInvariant())", '--app.rerank.provider=dashscope',
            '--app.rerank.url=https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank',
            '--app.rerank.model=gte-rerank-v2', '--app.rerank.api-key=${DASHSCOPE_API_KEY}')
    }
    else { $taskArguments += "--app.rerank.enabled=$($Rerank.ToString().ToLowerInvariant())" }
    $taskArguments += @("--app.agent.retrieval-first.rerank=$($Rerank.ToString().ToLowerInvariant())",
        "--app.agent.react.rerank=$($Rerank.ToString().ToLowerInvariant())")
    $taskArgsFile = Join-Path $taskRuntime ('run-' + [guid]::NewGuid().ToString('N') + '.args')
    [IO.File]::WriteAllLines($taskArgsFile, [string[]]($taskArguments | ForEach-Object { ConvertTo-PublicJavaArgument $_ }), (New-Object Text.UTF8Encoding($false)))
    & $taskJava "@$taskArgsFile"
    if ($LASTEXITCODE -ne 0) { throw "Public $Mode failed: exit $LASTEXITCODE" }
}
finally {
    $env:ELASTICSEARCH_USERNAME = $taskPreviousElasticUsername
    $env:ELASTICSEARCH_PASSWORD = $taskPreviousElasticPassword
    Pop-Location
}

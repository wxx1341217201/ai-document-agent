[CmdletBinding()]
param(
    [ValidateSet('Live', 'Preflight', 'Validate', 'Review', 'Audit')]
    [string]$Mode = 'Live',
    [string]$Dataset = 'benchmarks/agent/agent-tasks.jsonl',
    [string]$ReportDirectory = 'reports',
    [string]$SourceReport,
    [string]$Reviews,
    [string]$Deployment = 'unspecified',
    [ValidateRange(1, 10)]
    [int]$MaxConsecutiveErrors = 3,
    [ValidateRange(1, 3600)]
    [int]$MaxRunSeconds = 3600,
    [ValidateRange(0, 100000)]
    [int]$MaxTasksPerMode = 0,
    [bool]$Rerank = $true,
    [bool]$VerifyPlanEvidence = $true,
    [bool]$StructuredAnswers = $true,
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$')]
    [string]$ChatModel,
    [string]$AgentDeadline = '40s',
    [string]$ModelTimeout = '20s',
    [string]$VectorTimeout = '20s',
    [string]$KeywordTimeout = '5s',
    [string]$TotalTimeout = '25s',
    [string]$VectorCollection = 'ai_document_chunks_benchmark_m19a_v3',
    [string]$KeywordIndex = 'document_chunks_benchmark_m19a_v3',
    [string]$KeywordAlias = 'document_chunks_benchmark_m19a_v3_alias',
    [switch]$UseHttpsProxy,
    [switch]$RequireTarget
)
$ErrorActionPreference = 'Stop'
$taskRepo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if ($Mode -eq 'Review' -and ([string]::IsNullOrWhiteSpace($SourceReport) -or [string]::IsNullOrWhiteSpace($Reviews))) {
    throw 'Review requires -SourceReport <original.json> and -Reviews <human-review.jsonl>.'
}
if ($Mode -eq 'Audit' -and [string]::IsNullOrWhiteSpace($SourceReport)) { throw 'Audit requires -SourceReport <original.json>.' }
function ConvertTo-JavaArgument([string]$Value) {
    return '"' + $Value.Replace('\', '\\').Replace('"', '\"') + '"'
}
Push-Location $taskRepo
try {
    & .\mvnw.cmd '-Dmaven.compiler.fork=true' -DskipTests compile
    if ($LASTEXITCODE -ne 0) { throw "Compilation failed: exit $LASTEXITCODE" }
    $taskRuntime = Join-Path $taskRepo 'target/agent-benchmark'
    New-Item -ItemType Directory -Path $taskRuntime -Force | Out-Null
    $taskClasspathFile = Join-Path $taskRuntime 'classpath.txt'
    & .\mvnw.cmd -q dependency:build-classpath "-Dmdep.outputFile=$taskClasspathFile"
    if ($LASTEXITCODE -ne 0) { throw "Classpath generation failed: exit $LASTEXITCODE" }
    $taskClasspath = (Join-Path $taskRepo 'target/classes') + ';' + (Get-Content -LiteralPath $taskClasspathFile -Raw).Trim()
    $taskTempDirectory = Join-Path $taskRepo 'target/sandbox-temp'
    New-Item -ItemType Directory -Path $taskTempDirectory -Force | Out-Null
    $taskArguments = @("-Djava.io.tmpdir=$taskTempDirectory")
    if ($UseHttpsProxy) {
        if (-not $env:HTTPS_PROXY) { throw 'UseHttpsProxy requires HTTPS_PROXY in this process environment.' }
        $taskProxy = [Uri]$env:HTTPS_PROXY
        $taskArguments += @("-Dhttps.proxyHost=$($taskProxy.Host)", "-Dhttps.proxyPort=$($taskProxy.Port)")
        $Deployment += "; HTTPS proxy $($taskProxy.Host):$($taskProxy.Port)"
    }
    $taskArguments += @('-Xms64m', '-Xmx384m', '-XX:ActiveProcessorCount=2',
        '-cp', $taskClasspath, 'com.wxx.aidocumentagent.agent.benchmark.AgentBenchmarkRunner')
    switch ($Mode) {
        { $_ -in @('Live', 'Preflight') } {
            $taskArguments += @('--live', "--app.benchmark.agent.dataset=$Dataset",
                "--app.benchmark.agent.report-directory=$ReportDirectory", "--app.benchmark.agent.deployment=$Deployment",
                "--app.benchmark.agent.max-consecutive-errors=$MaxConsecutiveErrors",
                "--app.benchmark.agent.max-run-seconds=$MaxRunSeconds", "--app.benchmark.agent.max-tasks-per-mode=$MaxTasksPerMode",
                '--app.agent.plan.max-revisions=1', '--spring.ai.openai.chat.temperature=0.0',
                "--app.agent.retrieval-first.structured-answers=$($StructuredAnswers.ToString().ToLowerInvariant())",
                "--app.agent.react.structured-answers=$($StructuredAnswers.ToString().ToLowerInvariant())",
                "--app.agent.plan.structured-answers=$($StructuredAnswers.ToString().ToLowerInvariant())",
                "--app.agent.plan.verify-evidence=$($VerifyPlanEvidence.ToString().ToLowerInvariant())",
                "--app.agent.retrieval-first.deadline=$AgentDeadline", "--app.agent.react.deadline=$AgentDeadline",
                "--app.agent.plan.deadline=$AgentDeadline", "--app.agent.react.model-timeout=$ModelTimeout",
                "--app.agent.plan.model-timeout=$ModelTimeout",
                "--app.rag.read-timeout=$ModelTimeout", "--app.rag.chat-timeout=$ModelTimeout",
                "--spring.ai.openai.timeout=$ModelTimeout",
                "--app.rerank.enabled=$($Rerank.ToString().ToLowerInvariant())",
                "--app.agent.retrieval-first.rerank=$($Rerank.ToString().ToLowerInvariant())",
                "--app.agent.react.rerank=$($Rerank.ToString().ToLowerInvariant())",
                "--app.retrieval.vector-timeout=$VectorTimeout", "--app.retrieval.keyword-timeout=$KeywordTimeout",
                "--app.retrieval.total-timeout=$TotalTimeout",
                "--app.vector.qdrant.collection-name=$VectorCollection", "--app.keyword.index-name=$KeywordIndex",
                "--app.keyword.alias=$KeywordAlias")
            if ($Mode -eq 'Preflight') { $taskArguments += '--app.benchmark.agent.preflight-only=true' }
            if (-not [string]::IsNullOrWhiteSpace($ChatModel)) {
                $taskArguments += @("--spring.ai.openai.chat.model=$ChatModel", "--app.rag.model=$ChatModel")
            }
            if ($RequireTarget) { $taskArguments += '--app.benchmark.agent.require-target=true' }
        }
        'Validate' { $taskArguments += @('--validate-only', "--dataset=$Dataset", "--report-directory=$ReportDirectory") }
        'Review' { $taskArguments += @("--review-report=$SourceReport", "--reviews=$Reviews", "--report-directory=$ReportDirectory") }
        'Audit' { $taskArguments += @("--audit-report=$SourceReport", "--report-directory=$ReportDirectory") }
    }
    if ($RequireTarget -and $Mode -notin @('Live', 'Preflight')) { $taskArguments += '--require-target' }
    $taskArgsFile = Join-Path $taskRuntime ('run-' + [guid]::NewGuid().ToString('N') + '.args')
    $taskLines = $taskArguments | ForEach-Object { ConvertTo-JavaArgument $_ }
    [IO.File]::WriteAllLines($taskArgsFile, [string[]]$taskLines, (New-Object Text.UTF8Encoding($false)))
    & java "@$taskArgsFile"
    if ($LASTEXITCODE -ne 0) { throw "Agent benchmark failed: exit $LASTEXITCODE" }
}
finally { Pop-Location }

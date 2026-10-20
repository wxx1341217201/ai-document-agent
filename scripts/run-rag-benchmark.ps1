[CmdletBinding()]
param(
    [ValidateSet('Live', 'Preflight', 'Validate', 'Review')]
    [string]$Mode = 'Live',
    [string]$Dataset = 'benchmarks/rag/qa-dataset.jsonl',
    [string]$ReportDirectory = 'reports',
    [string]$SourceReport,
    [string]$Reviews,
    [string]$Deployment = 'unspecified',
    [ValidateRange(1, 20)]
    [int]$TopK = 8,
    [ValidateRange(1, 100)]
    [int]$CandidateTopK = 40,
    [bool]$QueryExpansion = $true,
    [string]$QueryExpansionTimeout = '30s',
    [ValidateRange(512, 100000)]
    [int]$MaxContextCharacters = 32000,
    [ValidateRange(64, 100000)]
    [int]$MaxChunkCharacters = 4500,
    [bool]$Rerank = $true,
    [string]$VectorTimeout = '2s',
    [string]$KeywordTimeout = '2s',
    [string]$TotalTimeout = '3s',
    [string]$VectorCollection = 'ai_document_chunks_benchmark_m19a_v3',
    [string]$KeywordIndex = 'document_chunks_benchmark_m19a_v3',
    [string]$KeywordAlias = 'document_chunks_benchmark_m19a_v3_alias',
    [switch]$UseHttpsProxy
)

$ErrorActionPreference = 'Stop'
$taskRepo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if ($Mode -eq 'Review' -and ([string]::IsNullOrWhiteSpace($SourceReport) -or [string]::IsNullOrWhiteSpace($Reviews))) {
    throw 'Review requires -SourceReport <original.json> and -Reviews <human-review.jsonl>.'
}

# Java argument files avoid Windows command length limits and shell expansion.
function ConvertTo-JavaArgument([string]$Value) {
    return '"' + $Value.Replace('\', '\\').Replace('"', '\"') + '"'
}

Push-Location $taskRepo
try {
    & .\mvnw.cmd '-Dmaven.compiler.fork=true' -DskipTests compile
    if ($LASTEXITCODE -ne 0) { throw "Compilation failed: exit $LASTEXITCODE" }
    $taskRuntime = Join-Path $taskRepo 'target/rag-benchmark'
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
    $taskArguments += @('-cp', $taskClasspath, 'com.wxx.aidocumentagent.rag.benchmark.RagAccuracyBenchmarkRunner')
    switch ($Mode) {
        { $_ -in @('Live', 'Preflight') } {
            $taskArguments += @(
                '--live',
                "--app.benchmark.rag.dataset=$Dataset",
                "--app.benchmark.rag.report-directory=$ReportDirectory",
                "--app.benchmark.rag.deployment=$Deployment",
                "--app.benchmark.rag.top-k=$TopK",
                "--app.benchmark.rag.rerank=$($Rerank.ToString().ToLowerInvariant())",
                "--app.retrieval.vector-top-k=$CandidateTopK",
                "--app.retrieval.keyword-top-k=$CandidateTopK",
                "--app.rag.query-expansion-enabled=$($QueryExpansion.ToString().ToLowerInvariant())",
                "--app.rag.query-expansion-timeout=$QueryExpansionTimeout",
                "--app.rag.max-context-characters=$MaxContextCharacters",
                "--app.rag.max-chunk-characters=$MaxChunkCharacters",
                "--app.retrieval.vector-timeout=$VectorTimeout",
                "--app.retrieval.keyword-timeout=$KeywordTimeout",
                "--app.retrieval.total-timeout=$TotalTimeout",
                '--spring.ai.openai.chat.temperature=0.0',
                "--app.vector.qdrant.collection-name=$VectorCollection",
                "--app.keyword.index-name=$KeywordIndex",
                "--app.keyword.alias=$KeywordAlias"
            )
            if ($Mode -eq 'Preflight') { $taskArguments += '--app.benchmark.rag.preflight-only=true' }
        }
        'Validate' { $taskArguments += @('--validate-only', "--dataset=$Dataset", "--report-directory=$ReportDirectory") }
        'Review' { $taskArguments += @("--review-report=$SourceReport", "--reviews=$Reviews", "--report-directory=$ReportDirectory") }
    }
    $taskArgsFile = Join-Path $taskRuntime ('run-' + [guid]::NewGuid().ToString('N') + '.args')
    $taskLines = $taskArguments | ForEach-Object { ConvertTo-JavaArgument $_ }
    [IO.File]::WriteAllLines($taskArgsFile, [string[]]$taskLines, (New-Object Text.UTF8Encoding($false)))
    & java "@$taskArgsFile"
    if ($LASTEXITCODE -ne 0) { throw "RAG benchmark failed: exit $LASTEXITCODE" }
}
finally {
    Pop-Location
}

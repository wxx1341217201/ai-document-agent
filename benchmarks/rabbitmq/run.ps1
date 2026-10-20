[CmdletBinding()]
param(
    [ValidateSet('Live', 'Validate')][string]$Mode = 'Live',
    [int[]]$BatchSizes = @(25, 50, 100),
    [int[]]$Consumers = @(1, 2, 4, 8),
    [ValidateRange(1, 3600)][int]$DurationSeconds = 90,
    [ValidateRange(2, 100)][int]$WarmupDocuments = 20,
    [ValidateRange(1, 100)][int]$Prefetch = 2,
    [ValidateRange(0, 50)][int]$ChunkDelayMs = 2,
    [ValidateRange(0, 100)][int]$BatchDelayMs = 2,
    [long]$Seed = 20261011,
    [string]$OutputDirectory = 'benchmarks/rabbitmq/results',
    [switch]$RequireTargets
)
$ErrorActionPreference = 'Stop'
$taskRepo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$taskJava = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java -ErrorAction Stop).Source }
function ConvertTo-JavaArgument([string]$Value) { return '"' + $Value.Replace('\', '\\').Replace('"', '\"') + '"' }
Push-Location $taskRepo
try {
    & .\mvnw.cmd '-Dmaven.compiler.fork=true' -DskipTests compile
    if ($LASTEXITCODE -ne 0) { throw 'M19E compilation failed.' }
    $taskRuntime = Join-Path $taskRepo 'target/rabbitmq-benchmark'
    New-Item -ItemType Directory -Path $taskRuntime -Force | Out-Null
    $taskClasspathFile = Join-Path $taskRuntime 'classpath.txt'
    & .\mvnw.cmd -q dependency:build-classpath "-Dmdep.outputFile=$taskClasspathFile"
    if ($LASTEXITCODE -ne 0) { throw 'M19E classpath generation failed.' }
    $taskClasspath = (Join-Path $taskRepo 'target/classes') + ';' + (Get-Content -LiteralPath $taskClasspathFile -Raw).Trim()
    $taskArguments = @('-cp', $taskClasspath, 'com.wxx.aidocumentagent.ingestion.benchmark.RabbitBenchmarkRunner',
        "--batch-sizes=$($BatchSizes -join ',')", "--consumers=$($Consumers -join ',')", "--duration-seconds=$DurationSeconds",
        "--warmup-documents=$WarmupDocuments", "--prefetch=$Prefetch", "--chunk-delay-ms=$ChunkDelayMs",
        "--batch-delay-ms=$BatchDelayMs", "--seed=$Seed", "--output-directory=$OutputDirectory")
    if ($Mode -eq 'Live') { $taskArguments += '--live' }
    if ($RequireTargets) { $taskArguments += '--require-targets' }
    $taskArgsFile = Join-Path $taskRuntime ('run-' + [guid]::NewGuid().ToString('N') + '.args')
    [IO.File]::WriteAllLines($taskArgsFile, [string[]]($taskArguments | ForEach-Object { ConvertTo-JavaArgument $_ }), (New-Object Text.UTF8Encoding($false)))
    & $taskJava "@$taskArgsFile"
    if ($LASTEXITCODE -ne 0) { throw "M19E benchmark exit $LASTEXITCODE. Inspect $OutputDirectory and target/rabbitmq-benchmark." }
}
finally { Pop-Location }

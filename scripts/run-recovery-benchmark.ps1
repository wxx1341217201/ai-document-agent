[CmdletBinding()]
param(
    [ValidateSet('Live', 'Validate')]
    [string]$Mode = 'Live',
    [ValidateRange(7, 100000)]
    [int]$Samples = 1000,
    [long]$Seed = 20261002,
    [ValidateRange(1, 8)]
    [int]$Parallelism = 1,
    [string]$CompleteReport,
    [string]$ReportDirectory = 'reports',
    [ValidateRange(40, 600)]
    [int]$RecoveryTimeoutSeconds = 90,
    [switch]$KeepEnvironment
)
$ErrorActionPreference = 'Stop'
$taskRepo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if ($CompleteReport -and $Mode -ne 'Live') { throw 'CompleteReport requires Live mode.' }
$taskJava = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java -ErrorAction Stop).Source }
if (-not (Test-Path -LiteralPath $taskJava -PathType Leaf)) { throw 'JAVA_HOME does not contain bin/java.exe.' }
function ConvertTo-JavaArgument([string]$Value) {
    return '"' + $Value.Replace('\', '\\').Replace('"', '\"') + '"'
}
Push-Location $taskRepo
try {
    & .\mvnw.cmd '-Dmaven.compiler.fork=true' -DskipTests compile
    if ($LASTEXITCODE -ne 0) { throw "Compilation failed: exit $LASTEXITCODE" }
    $taskRuntime = Join-Path $taskRepo 'target/recovery-benchmark'
    New-Item -ItemType Directory -Path $taskRuntime -Force | Out-Null
    $taskClasspathFile = Join-Path $taskRuntime 'classpath.txt'
    & .\mvnw.cmd -q dependency:build-classpath "-Dmdep.outputFile=$taskClasspathFile"
    if ($LASTEXITCODE -ne 0) { throw "Classpath generation failed: exit $LASTEXITCODE" }
    $taskClasspath = (Join-Path $taskRepo 'target/classes') + ';' + (Get-Content -LiteralPath $taskClasspathFile -Raw).Trim()
    $taskArguments = @('-cp', $taskClasspath, 'com.wxx.aidocumentagent.recovery.benchmark.RecoveryBenchmarkRunner',
        "--samples=$Samples", "--seed=$Seed", "--parallelism=$Parallelism", "--report-directory=$ReportDirectory", "--recovery-timeout-seconds=$RecoveryTimeoutSeconds")
    if ($Mode -eq 'Live') { $taskArguments += '--live' } else { $taskArguments += '--validate-only' }
    if ($KeepEnvironment) { $taskArguments += '--keep-environment' }
    if ($CompleteReport) { $taskArguments += "--complete-report=$CompleteReport" }
    $taskArgsFile = Join-Path $taskRuntime ('run-' + [guid]::NewGuid().ToString('N') + '.args')
    $taskLines = $taskArguments | ForEach-Object { ConvertTo-JavaArgument $_ }
    [IO.File]::WriteAllLines($taskArgsFile, [string[]]$taskLines, (New-Object Text.UTF8Encoding($false)))
    & $taskJava "@$taskArgsFile"
    $taskBenchmarkExit = $LASTEXITCODE
    if ($taskBenchmarkExit -ne 0) { throw "Recovery benchmark did not complete successfully: exit $taskBenchmarkExit. Inspect reports/recovery-*.json and target/recovery-benchmark/<run-id>/*.log." }
}
finally { Pop-Location }

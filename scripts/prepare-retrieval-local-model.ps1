[CmdletBinding()]
param([string]$PythonPath = '')
$ErrorActionPreference = 'Stop'
$taskRepo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $PythonPath) {
    $taskBundledPython = Join-Path $env:USERPROFILE '.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
    $PythonPath = if (Test-Path -LiteralPath $taskBundledPython) { $taskBundledPython } else { (Get-Command python -ErrorAction Stop).Source }
}
$taskSavedTemp = $env:TEMP
$taskSavedTmp = $env:TMP
Push-Location $taskRepo
try {
    $taskCache = Join-Path $taskRepo 'target/retrieval-benchmark'
    $taskModel = Join-Path $taskCache 'models/mmarco'
    $taskTemp = Join-Path $taskCache 'pip-temp'
    New-Item -ItemType Directory -Path $taskModel, $taskTemp -Force | Out-Null
    $env:TEMP = $taskTemp
    $env:TMP = $taskTemp
    & $PythonPath -m pip install --disable-pip-version-check --no-cache-dir --target (Join-Path $taskCache 'python') -r benchmarks/retrieval/requirements-local.txt
    if ($LASTEXITCODE -ne 0) { throw 'Cannot install workspace-local ONNX dependencies.' }
    $taskBase = 'https://huggingface.co/cross-encoder/mmarco-mMiniLMv2-L12-H384-v1/resolve/1427fd652930e4ba29e8149678df786c240d8825/'
    $taskFiles = @{ 'tokenizer.json' = 'tokenizer.json'; 'config.json' = 'config.json'; 'model.onnx' = 'onnx/model_quint8_avx2.onnx' }
    foreach ($taskFile in $taskFiles.Keys) {
        $taskDestination = Join-Path $taskModel $taskFile
        if (-not (Test-Path -LiteralPath $taskDestination)) {
            $taskDownload = "$taskDestination.download"
            Invoke-WebRequest -Uri ($taskBase + $taskFiles[$taskFile]) -OutFile $taskDownload -TimeoutSec 600
            Move-Item -LiteralPath $taskDownload -Destination $taskDestination
        }
        Get-FileHash -LiteralPath $taskDestination -Algorithm SHA256 | Select-Object Path,Hash
    }
}
finally { $env:TEMP = $taskSavedTemp; $env:TMP = $taskSavedTmp; Pop-Location }

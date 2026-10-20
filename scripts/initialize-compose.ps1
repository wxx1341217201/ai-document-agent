[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $projectRoot '.env'
if (-not (Test-Path -LiteralPath $envFile)) {
    Copy-Item -LiteralPath (Join-Path $projectRoot '.env.example') -Destination $envFile
    Write-Output 'Created .env from .env.example. Configure the backend provider keys before starting.'
}

$secretDirectory = Join-Path $projectRoot 'secrets'
$secretFile = Join-Path $secretDirectory 'progress_subscription_token'
if (-not (Test-Path -LiteralPath $secretFile)) {
    New-Item -ItemType Directory -Path $secretDirectory -Force | Out-Null
    $randomBytes = New-Object byte[] 32
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $generator.GetBytes($randomBytes)
    } finally {
        $generator.Dispose()
    }
    $token = [BitConverter]::ToString($randomBytes).Replace('-', '').ToLowerInvariant()
    [System.IO.File]::WriteAllText($secretFile, $token, [System.Text.UTF8Encoding]::new($false))
    Write-Output 'Created the server-side progress secret (value is not displayed).'
} else {
    Write-Output 'Kept the existing progress secret.'
}

Write-Output 'Next: docker compose config --quiet; docker compose up -d --build'

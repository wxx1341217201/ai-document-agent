[CmdletBinding()]
param(
    [ValidatePattern('^(?!-)[A-Za-z0-9_.@-]+$')][string]$SshHost = 'aliyun',
    [ValidatePattern('^/[A-Za-z0-9_./-]+$')][string]$RemoteDirectory = '/root/projects/ai-document-agent-cloud',
    [switch]$PrepareOnly,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'hybrid-common.ps1')
if ($RemoteDirectory -eq '/' -or '..' -in $RemoteDirectory.Split('/')) {
    throw 'Use a dedicated absolute cloud deployment directory.'
}
if (-not (Test-Path -LiteralPath (Join-Path $script:HybridProjectRoot '.env.hybrid.cloud'))) {
    throw 'Run scripts/initialize-hybrid.ps1 first.'
}
$configuration = Get-HybridCloudConfiguration
Assert-HybridProviderKeys $configuration.services.backend.environment
Assert-HybridSingleBackend $configuration

if (-not $PrepareOnly) {
    & ssh -T -o BatchMode=yes -o StrictHostKeyChecking=yes -o ConnectTimeout=10 $SshHost 'true'
    if ($LASTEXITCODE -ne 0) {
        throw "SSH authentication to '$SshHost' failed; use -PrepareOnly to build locally while fixing SSH."
    }
}
if (-not $SkipBuild) {
    Write-Output 'Building current backend source locally for Linux amd64.'
    & docker build --platform linux/amd64 --tag ai-document-agent-backend:hybrid $script:HybridProjectRoot
    if ($LASTEXITCODE -ne 0) { throw 'Backend image build failed.' }
    Write-Output 'Building current frontend source locally for Linux amd64.'
    & docker build --platform linux/amd64 --tag ai-document-agent-frontend:hybrid (Join-Path $script:HybridProjectRoot 'frontend')
    if ($LASTEXITCODE -ne 0) { throw 'Frontend image build failed.' }
}

$bundleDirectory = Join-Path $script:HybridProjectRoot 'artifacts/hybrid'
$bundleSecrets = Join-Path $bundleDirectory 'secrets'
New-Item -ItemType Directory -Path $bundleSecrets -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $script:HybridProjectRoot 'compose.hybrid.cloud.yaml') -Destination $bundleDirectory
Copy-Item -LiteralPath (Join-Path $script:HybridProjectRoot '.env.hybrid.cloud') -Destination $bundleDirectory
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'start-hybrid-cloud.sh') -Destination $bundleDirectory
$expectedSecret = Join-Path (Join-Path $script:HybridProjectRoot 'secrets') 'hybrid_progress_subscription_token'
if ($configuration.secrets.progress_subscription_token.file -ne $expectedSecret) {
    throw 'Publishing currently requires the default hybrid progress secret path.'
}
Copy-Item -LiteralPath $expectedSecret -Destination (Join-Path $bundleSecrets 'hybrid_progress_subscription_token')

$imageArchive = Join-Path $bundleDirectory 'images.tar'
Write-Output 'Exporting runtime images (this can take a few minutes).'
& docker image save --output $imageArchive ai-document-agent-backend:hybrid ai-document-agent-frontend:hybrid
if ($LASTEXITCODE -ne 0) { throw 'Image export failed.' }
$archiveHash = (Get-FileHash -LiteralPath $imageArchive -Algorithm SHA256).Hash.ToLowerInvariant()
Write-HybridUtf8File -Path (Join-Path $bundleDirectory 'images.tar.sha256') -Value ("$archiveHash  images.tar" + [char]10)
Write-Output "Prepared private deployment bundle: $bundleDirectory"
Write-Output 'The bundle contains application credentials. It is Git-ignored and must not be shared.'
if ($PrepareOnly) {
    Write-Output 'Local preparation finished. No files were uploaded and no cloud resources were changed.'
    return
}

$sshOptions = @('-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes', '-o', 'ConnectTimeout=10')
& ssh -T @sshOptions $SshHost "umask 077; mkdir -p '$RemoteDirectory/secrets'"
if ($LASTEXITCODE -ne 0) { throw 'Could not prepare the cloud deployment directory.' }
$bundleFiles = @('compose.hybrid.cloud.yaml', '.env.hybrid.cloud', 'start-hybrid-cloud.sh', 'images.tar', 'images.tar.sha256') |
    ForEach-Object { Join-Path $bundleDirectory $_ }
Write-Output 'Uploading the deployment bundle to the configured cloud server.'
& scp -B @sshOptions @bundleFiles "${SshHost}:$RemoteDirectory/"
if ($LASTEXITCODE -ne 0) { throw 'Bundle upload failed; cloud services were not restarted.' }
& scp -B @sshOptions (Join-Path $bundleSecrets 'hybrid_progress_subscription_token') "${SshHost}:$RemoteDirectory/secrets/"
if ($LASTEXITCODE -ne 0) { throw 'Progress secret upload failed; cloud services were not restarted.' }

Write-Output 'Starting the cloud services; leave start-hybrid-tunnel.ps1 running in another terminal.'
& ssh -T @sshOptions $SshHost "cd '$RemoteDirectory' && bash start-hybrid-cloud.sh"
if ($LASTEXITCODE -ne 0) { throw 'Cloud startup did not pass verification. Inspect the cloud Compose status.' }
Write-Output 'Cloud deployment verified. Open http://127.0.0.1:18088 while the tunnel is connected.'

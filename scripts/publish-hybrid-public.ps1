[CmdletBinding()]
param(
    [ValidatePattern('^(?!-)[A-Za-z0-9_.@-]+$')][string]$SshHost = 'aliyun',
    [ValidatePattern('^/[A-Za-z0-9_./-]+$')][string]$RemoteDirectory = '/root/projects/ai-document-agent-cloud',
    [ValidatePattern('^[0-9.]+$')][string]$PublicHost = '47.111.124.154',
    [switch]$PrepareOnly,
    [switch]$GatewayOnly
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'hybrid-common.ps1')
$publicAddress = $null
if (-not [System.Net.IPAddress]::TryParse($PublicHost, [ref]$publicAddress) -or
    $publicAddress.AddressFamily -ne [System.Net.Sockets.AddressFamily]::InterNetwork -or
    $PublicHost -eq '0.0.0.0' -or [System.Net.IPAddress]::IsLoopback($publicAddress) -or
    $RemoteDirectory -eq '/' -or '..' -in $RemoteDirectory.Split('/')) {
    throw 'Use a public IPv4 address and a dedicated cloud directory.'
}
if (-not (Test-Path -LiteralPath (Join-Path $script:HybridProjectRoot '.env.hybrid.cloud'))) {
    throw 'Deploy the private hybrid application before enabling its public gateway.'
}
Assert-HybridSingleBackend (Get-HybridCloudConfiguration)
$caddyImage = 'caddy:2.11.7-alpine'
Invoke-HybridDocker -Arguments @('image', 'inspect', $caddyImage) | Out-Null
if (-not $GatewayOnly) {
    & docker build --platform linux/amd64 --tag ai-document-agent-frontend:hybrid (Join-Path $script:HybridProjectRoot 'frontend')
    if ($LASTEXITCODE -ne 0) { throw 'Frontend image build failed.' }
}
$credentialPath = Join-Path $script:HybridProjectRoot 'secrets/hybrid_public_access.json'
if (Test-Path -LiteralPath $credentialPath) {
    $credentials = Get-Content -LiteralPath $credentialPath -Raw | ConvertFrom-Json
    if ($credentials.url -ne "https://$PublicHost" -or $credentials.username -notmatch '^[a-z][a-z0-9_]{2,31}$' -or
        [string]::IsNullOrEmpty($credentials.password) -or $credentials.password -match '[\r\n]' -or
        [System.Text.Encoding]::UTF8.GetByteCount([string]$credentials.password) -lt 8 -or
        [System.Text.Encoding]::UTF8.GetByteCount([string]$credentials.password) -gt 72 -or
        $credentials.passwordHash -notmatch '^\$2[aby]\$\d{2}\$[./A-Za-z0-9]{53}$') {
        throw 'Existing public credentials do not match the requested host or format. They were not changed.'
    }
    Write-Output 'Kept the existing public access credentials.'
} else {
    $password = (New-HybridRandomToken).Substring(0, 32)
    # PowerShell appends CRLF to native stdin. Generated passwords are hex, so
    # strip CR before hashing and retain the LF that Caddy's line reader requires.
    $hashOutput = Invoke-HybridDocker -Arguments @('run', '--rm', '-i', '--entrypoint', 'sh', $caddyImage,
        '-c', 'tr -d "\r" | caddy hash-password --algorithm bcrypt') -InputText $password
    $passwordHash = @($hashOutput -split '\r?\n' | Where-Object { $_ -match '^\$2[aby]\$\d{2}\$[./A-Za-z0-9]{53}$' })
    if ($passwordHash.Count -ne 1) { throw 'Could not generate the public access password hash.' }
    $credentials = [pscustomobject]@{
        url = "https://$PublicHost"
        username = 'aida'
        password = $password
        passwordHash = $passwordHash[0]
    }
    Write-HybridUtf8File -Path $credentialPath -Value ($credentials | ConvertTo-Json)
    Write-Output 'Created the private public-access credential file; password is not printed.'
}
# Retain browser sessions across ordinary releases. Rotate the random bearer when
# the login identity/hash changes so previous sessions cannot bypass a new password.
$identityBytes = [System.Text.Encoding]::UTF8.GetBytes($credentials.username + "`n" + $credentials.passwordHash)
$identityHash = [Convert]::ToHexString([System.Security.Cryptography.SHA256]::HashData($identityBytes)).ToLowerInvariant()
if ($credentials.sessionToken -notmatch '^[a-f0-9]{64}$' -or $credentials.sessionCredentialHash -ne $identityHash) {
    $credentials | Add-Member -NotePropertyName sessionToken -NotePropertyValue (New-HybridRandomToken) -Force
    $credentials | Add-Member -NotePropertyName sessionCredentialHash -NotePropertyValue $identityHash -Force
    Write-HybridUtf8File -Path $credentialPath -Value ($credentials | ConvertTo-Json)
    Write-Output 'Prepared the private browser-session secret; login credentials are unchanged.'
}
$publicValues = [ordered]@{
    PUBLIC_HOST = $PublicHost
    PUBLIC_USERNAME = $credentials.username
    PUBLIC_PASSWORD_HASH = $credentials.passwordHash
    PUBLIC_SESSION_TOKEN = $credentials.sessionToken
}
$publicEnvPath = Join-Path $script:HybridProjectRoot '.env.hybrid.public'
Write-HybridEnvironmentFile -Path $publicEnvPath -Values $publicValues

$bundleDirectory = Join-Path $script:HybridProjectRoot 'artifacts/hybrid/public'
$configDirectory = Join-Path $bundleDirectory 'deploy/hybrid-public'
New-Item -ItemType Directory -Path $configDirectory -Force | Out-Null
foreach ($file in @('compose.hybrid.cloud.yaml', 'compose.hybrid.public.yaml', '.env.hybrid.public')) {
    Copy-Item -LiteralPath (Join-Path $script:HybridProjectRoot $file) -Destination $bundleDirectory
}
Copy-Item -LiteralPath (Join-Path $script:HybridProjectRoot 'deploy/hybrid-public/Caddyfile') -Destination $configDirectory
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'start-hybrid-public.sh') -Destination $bundleDirectory
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'start-hybrid-cloud.sh') -Destination $bundleDirectory
if (-not $GatewayOnly) {
    $archivePath = Join-Path $bundleDirectory 'public-images.tar'
    Invoke-HybridDocker -Arguments @('image', 'save', '--output', $archivePath, $caddyImage, 'ai-document-agent-frontend:hybrid') | Out-Null
    $archiveHash = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-HybridUtf8File -Path (Join-Path $bundleDirectory 'public-images.tar.sha256') -Value ("$archiveHash  public-images.tar" + [char]10)
}
# Resolve base application files against the project; private env and secret are never copied by this publisher.
Invoke-HybridDocker -Arguments @('compose', '--project-directory', $script:HybridProjectRoot,
    '--env-file', (Join-Path $script:HybridProjectRoot '.env.hybrid.cloud'),
    '-f', (Join-Path $script:HybridProjectRoot 'compose.hybrid.cloud.yaml'),
    '-f', (Join-Path $script:HybridProjectRoot 'compose.hybrid.public.yaml'), 'config', '--quiet') | Out-Null
Write-Output "Prepared public gateway for https://$PublicHost . Credentials: $credentialPath"
if ($PrepareOnly) { return }

$sshOptions = @('-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes', '-o', 'ConnectTimeout=10',
    '-o', 'ServerAliveInterval=10', '-o', 'ServerAliveCountMax=3')
& ssh -T @sshOptions $SshHost "umask 077; mkdir -p '$RemoteDirectory/deploy/hybrid-public'"
if ($LASTEXITCODE -ne 0) { throw 'Could not prepare the existing cloud deployment directory.' }
$bundleNames = if ($GatewayOnly) {
    @('compose.hybrid.public.yaml', '.env.hybrid.public', 'start-hybrid-public.sh')
} else {
    @('compose.hybrid.cloud.yaml', 'compose.hybrid.public.yaml', '.env.hybrid.public',
        'start-hybrid-public.sh', 'start-hybrid-cloud.sh', 'public-images.tar', 'public-images.tar.sha256')
}
$bundleFiles = $bundleNames | ForEach-Object { Join-Path $bundleDirectory $_ }
& scp -B @sshOptions @bundleFiles "${SshHost}:$RemoteDirectory/"
if ($LASTEXITCODE -ne 0) { throw 'Public gateway upload failed.' }
& scp -B @sshOptions (Join-Path $configDirectory 'Caddyfile') "${SshHost}:$RemoteDirectory/deploy/hybrid-public/"
if ($LASTEXITCODE -ne 0) { throw 'Public gateway configuration upload failed.' }
$startOption = if ($GatewayOnly) { ' --gateway-only' } else { '' }
& ssh -T @sshOptions $SshHost "cd '$RemoteDirectory' && bash start-hybrid-public.sh$startOption"
if ($LASTEXITCODE -ne 0) { throw 'Public gateway startup failed. The private application and document volume are retained.' }
Write-Output "Gateway started. Final public HTTPS checks are required: https://$PublicHost"

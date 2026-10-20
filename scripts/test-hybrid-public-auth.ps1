[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'hybrid-common.ps1')
$projectRoot = $script:HybridProjectRoot
$artifactDirectory = Join-Path $projectRoot 'artifacts/hybrid/auth-check'
New-Item -ItemType Directory -Path $artifactDirectory -Force | Out-Null

# Exercise the real gateway authentication handlers on loopback, with a fixed
# response replacing the application. No database, model or ECS service is used.
$source = Get-Content (Join-Path $projectRoot 'deploy/hybrid-public/Caddyfile') -Raw
$start = $source.IndexOf("`t# Browser Basic-auth")
if ($start -lt 0) { throw 'Authentication route not found.' }
$fixture = $source.Substring($start)
$fixture = [regex]::Replace($fixture,
    '(?ms)^\t\treverse_proxy 127\.0\.0\.1:8088 \{.*?^\t\t\}', "`t`trespond AUTHENTICATED 200")
if ($fixture -match 'reverse_proxy|tls \{') { throw 'Auth fixture must not connect to an upstream.' }
$fixturePath = Join-Path $artifactDirectory 'Caddyfile'
Write-HybridUtf8File -Path $fixturePath -Value ("http://:80 {`n" + $fixture)
$configuration = Invoke-HybridDocker -Arguments @('compose', '--project-directory', $projectRoot,
    '--env-file', (Join-Path $projectRoot '.env.hybrid.cloud'),
    '-f', (Join-Path $projectRoot 'compose.hybrid.cloud.yaml'),
    '-f', (Join-Path $projectRoot 'compose.hybrid.public.yaml'), 'config', '--format', 'json') | ConvertFrom-Json
$values = ConvertTo-HybridEnvironmentMap $configuration.services.gateway.environment
$credentials = Get-Content (Join-Path $projectRoot 'secrets/hybrid_public_access.json') -Raw | ConvertFrom-Json
$auth = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(
    $credentials.username + ':' + $credentials.password))
$prefix = 'aida-session-check-' + [guid]::NewGuid().ToString('N').Substring(0, 10)
$checks = [ordered]@{}
foreach ($phase in @('configured', 'missing-secret')) {
    $name = $prefix + '-' + $phase
    $port = if ($phase -eq 'configured') { 18883 } else { 18884 }
    $arguments = @('run', '-d', '--name', $name, '--memory', '64m', '--publish', "127.0.0.1:${port}:80",
        '--mount', "type=bind,source=$fixturePath,target=/etc/caddy/Caddyfile,readonly")
    foreach ($key in @('PUBLIC_USERNAME', 'PUBLIC_PASSWORD_HASH', 'PUBLIC_SESSION_TOKEN')) {
        if ($phase -eq 'missing-secret' -and $key -eq 'PUBLIC_SESSION_TOKEN') { continue }
        $arguments += @('--env', ($key + '=' + $values[$key]))
    }
    $arguments += @('caddy:2.11.7-alpine', 'caddy', 'run', '--config', '/etc/caddy/Caddyfile', '--adapter', 'caddyfile')
    try {
        Invoke-HybridDocker -Arguments $arguments | Out-Null
        $url = "http://127.0.0.1:$port/"
        $anonymous = $null
        for ($attempt = 0; $attempt -lt 15; $attempt++) {
            try { $anonymous = Invoke-WebRequest $url -SkipHttpErrorCheck -TimeoutSec 2; break }
            catch { Start-Sleep -Milliseconds 200 }
        }
        if (-not $anonymous -or [int]$anonymous.StatusCode -ne 401 -or $anonymous.Headers['Set-Cookie']) {
            throw 'Anonymous request received access or a session.'
        }
        if ($phase -eq 'missing-secret') {
            $empty = Invoke-WebRequest $url -Headers @{ Cookie = '__Host-aida_session=' } -SkipHttpErrorCheck -TimeoutSec 3
            if ([int]$empty.StatusCode -ne 401) { throw 'Missing deployment secret failed open.' }
            $checks['missingSecretFailsClosed'] = $true
            continue
        }
        $wrongAuth = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(
            $credentials.username + ':incorrect-session-check-password'))
        $wrong = Invoke-WebRequest $url -Headers @{ Authorization = $wrongAuth } -SkipHttpErrorCheck -TimeoutSec 3
        if ([int]$wrong.StatusCode -ne 401 -or $wrong.Headers['Set-Cookie']) {
            throw 'Wrong password received access or a session.'
        }
        $login = Invoke-WebRequest $url -Headers @{ Authorization = $auth } -SkipHttpErrorCheck -TimeoutSec 3
        $cookieHeader = [string]($login.Headers['Set-Cookie'] -join '; ')
        $cacheControl = $login.Headers['Cache-Control'] -join ','
        if ([int]$login.StatusCode -ne 200 -or
            $cookieHeader -notmatch '__Host-aida_session=[0-9a-f]{64}; Path=/; Secure; HttpOnly; SameSite=Strict' -or
            $cacheControl -notmatch '\bprivate\b' -or $cacheControl -notmatch '\bno-store\b') {
            throw 'Valid login did not issue a protected, non-cacheable session.'
        }
        $cookie = $cookieHeader.Split(';')[0]
        $session = Invoke-WebRequest $url -Headers @{ Cookie = $cookie } -SkipHttpErrorCheck -TimeoutSec 3
        if ([int]$session.StatusCode -ne 200 -or $session.Headers['WWW-Authenticate']) {
            throw 'Cookie-only session was challenged.'
        }
        $forged = Invoke-WebRequest $url -Headers @{ Cookie = ('__Host-aida_session=' + ('0' * 64)) } -SkipHttpErrorCheck -TimeoutSec 3
        if ([int]$forged.StatusCode -ne 401 -or $forged.Headers['Set-Cookie']) {
            throw 'Forged cookie received access or a session.'
        }
        $checks['anonymousRejected'] = $true
        $checks['wrongPasswordRejectedWithoutCookie'] = $true
        $checks['validLoginIssuesSecureHttpOnlyHostSession'] = $true
        $checks['loginResponseNotCacheable'] = $true
        $checks['cookieOnlyAccessWithoutChallenge'] = $true
        $checks['forgedCookieRejected'] = $true
    } finally {
        Invoke-HybridDocker -Arguments @('rm', '-f', $name) | Out-Null
    }
}
Write-HybridUtf8File -Path (Join-Path $artifactDirectory 'result.json') -Value ($checks | ConvertTo-Json)
$checks | ConvertTo-Json
Write-Output 'Temporary auth containers removed; application containers and volumes were not changed.'

$script:HybridProjectRoot = Split-Path -Parent $PSScriptRoot

function Invoke-HybridDocker {
    param([Parameter(Mandatory)][string[]]$Arguments, [string]$InputText)
    if ($PSBoundParameters.ContainsKey('InputText')) {
        $result = $InputText | & docker @Arguments 2>&1 | Out-String
    } else {
        $result = & docker @Arguments 2>&1 | Out-String
    }
    if ($LASTEXITCODE -ne 0) {
        # SQL/config diagnostics may contain credentials; do not print captured output.
        throw "Docker operation failed (exit $LASTEXITCODE). Check Docker and the local configuration; captured output is withheld because it may contain credentials."
    }
    return $result
}

function Get-HybridWindowsComposeArguments {
    return @('compose', '--project-directory', $script:HybridProjectRoot,
        '--project-name', 'ai-document-agent',
        '--env-file', (Join-Path $script:HybridProjectRoot '.env'),
        '-f', (Join-Path $script:HybridProjectRoot 'compose.yaml'),
        '-f', (Join-Path $script:HybridProjectRoot 'compose.hybrid.windows.yaml'))
}

function Get-HybridCloudConfiguration {
    $arguments = @('compose', '--project-directory', $script:HybridProjectRoot,
        '--env-file', (Join-Path $script:HybridProjectRoot '.env.hybrid.cloud'),
        '-f', (Join-Path $script:HybridProjectRoot 'compose.hybrid.cloud.yaml'),
        'config', '--format', 'json')
    $json = Invoke-HybridDocker -Arguments $arguments
    return ($json | ConvertFrom-Json)
}

function ConvertTo-HybridEnvironmentMap {
    param([Parameter(Mandatory)]$EnvironmentObject)
    $values = [ordered]@{}
    foreach ($property in $EnvironmentObject.PSObject.Properties) {
        # "compose config" doubles dollars so its output can be used as Compose input.
        # Restore runtime values before copying credentials into another env file.
        $values[$property.Name] = ([string]$property.Value).Replace('$$', '$')
    }
    return $values
}

function New-HybridRandomToken {
    $bytes = New-Object byte[] 32
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
    return [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
}

function Write-HybridUtf8File {
    param([Parameter(Mandatory)][string]$Path, [AllowEmptyString()][string]$Value)
    [System.IO.File]::WriteAllText($Path, $Value, [System.Text.UTF8Encoding]::new($false))
}

function Write-HybridEnvironmentFile {
    param([Parameter(Mandatory)][string]$Path,
          [Parameter(Mandatory)][System.Collections.IDictionary]$Values)
    $lines = @('# Private cloud runtime settings. Never commit or publish this file.')
    foreach ($key in $Values.Keys) {
        $value = [string]$Values[$key]
        if ($key -notmatch '^[A-Z][A-Z0-9_]*$' -or $value -match '[\r\n]') {
            throw 'Invalid environment entry; multiline values are not supported.'
        }
        # Double-quoted dotenv values need escapes before Compose interpolation.
        # This also handles a literal trailing backslash, which single quotes cannot.
        $escaped = $value.Replace('\', '\\').Replace('"', '\"').Replace('$', '\$')
        $lines += $key + '="' + $escaped + '"'
    }
    Write-HybridUtf8File -Path $Path -Value (($lines -join [char]10) + [char]10)
}

function Test-HybridConfiguredKey {
    param([AllowEmptyString()][string]$Value)
    return (-not [string]::IsNullOrWhiteSpace($Value) -and
        $Value -notmatch '^(your-|change-me|replace-|generated-|copy-)')
}

function Assert-HybridProviderKeys {
    param([Parameter(Mandatory)]$EnvironmentObject)
    $values = ConvertTo-HybridEnvironmentMap $EnvironmentObject
    $chatKey = if ($values['RAG_CHAT_API_KEY']) { $values['RAG_CHAT_API_KEY'] } else { $values['OPENAI_API_KEY'] }
    $embeddingKey = if ($values['VECTOR_EMBEDDING_API_KEY']) { $values['VECTOR_EMBEDDING_API_KEY'] } else { $values['DASHSCOPE_API_KEY'] }
    if (-not (Test-HybridConfiguredKey $chatKey) -or -not (Test-HybridConfiguredKey $embeddingKey)) {
        throw 'Configure valid Chat and Embedding provider keys in .env.hybrid.cloud before publishing. No key values were printed.'
    }
}

function Assert-HybridSingleBackend {
    param([Parameter(Mandatory)]$Configuration)
    $values = ConvertTo-HybridEnvironmentMap $Configuration.services.backend.environment
    if ($values['HYBRID_DATA_MODE'] -ne 'original') { return }
    $running = Invoke-HybridDocker -Arguments @('ps',
        '--filter', 'label=com.docker.compose.project=ai-document-agent',
        '--filter', 'label=com.docker.compose.service=backend', '--format', '{{.Names}}')
    if (-not [string]::IsNullOrWhiteSpace($running)) {
        throw 'Original-data mode requires the Windows backend to stay stopped. The ECS backend owns the original queue and document storage; see docs/hybrid-deployment.md.'
    }
}

function Get-HybridTunnelMappings {
    return @(
        [pscustomobject]@{ Service = 'MySQL'; RemotePort = 13307; LocalPort = 3307 }
        [pscustomobject]@{ Service = 'Redis'; RemotePort = 16380; LocalPort = 6380 }
        [pscustomobject]@{ Service = 'RabbitMQ'; RemotePort = 15673; LocalPort = 5672 }
        [pscustomobject]@{ Service = 'Qdrant HTTP'; RemotePort = 16333; LocalPort = 6333 }
        [pscustomobject]@{ Service = 'Qdrant gRPC'; RemotePort = 16334; LocalPort = 6334 }
        [pscustomobject]@{ Service = 'Elasticsearch'; RemotePort = 19200; LocalPort = 9200 }
    )
}

function Get-HybridTunnelArguments {
    param([Parameter(Mandatory)][string]$SshHost)
    $arguments = @('-N', '-T', '-o', 'BatchMode=yes',
        '-o', 'StrictHostKeyChecking=yes', '-o', 'ConnectTimeout=10',
        '-o', 'ExitOnForwardFailure=yes',
        '-o', 'ServerAliveInterval=30', '-o', 'ServerAliveCountMax=3')
    foreach ($mapping in (Get-HybridTunnelMappings)) {
        $arguments += @('-R', "127.0.0.1:$($mapping.RemotePort):127.0.0.1:$($mapping.LocalPort)")
    }
    # Avoid the existing local application's 8088 port.
    $arguments += @('-L', '127.0.0.1:18088:127.0.0.1:8088', $SshHost)
    return $arguments
}

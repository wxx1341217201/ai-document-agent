[CmdletBinding()]
param([switch]$SkipStart)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'hybrid-common.ps1')
$composeArguments = Get-HybridWindowsComposeArguments

if (-not (Test-Path -LiteralPath (Join-Path $script:HybridProjectRoot '.env'))) {
    throw 'Configure the existing root .env first.'
}
$localJson = Invoke-HybridDocker -Arguments ($composeArguments + @('--profile', 'local-app', 'config', '--format', 'json'))
$localConfiguration = $localJson | ConvertFrom-Json
$localValues = ConvertTo-HybridEnvironmentMap $localConfiguration.services.backend.environment

$cloudEnvPath = Join-Path $script:HybridProjectRoot '.env.hybrid.cloud'
$secretDirectory = Join-Path $script:HybridProjectRoot 'secrets'
$secretPath = Join-Path $secretDirectory 'hybrid_progress_subscription_token'
if (-not (Test-Path -LiteralPath $cloudEnvPath)) {
    $cloudValues = [ordered]@{}
    foreach ($key in $localValues.Keys) {
        if ($key -match '^(OPENAI_|DASHSCOPE_|RAG_|VECTOR_EMBEDDING_|RERANK_|AGENT_|CONVERSATION_)' -or
            $key -in @('REDIS_PASSWORD', 'ELASTICSEARCH_USERNAME', 'ELASTICSEARCH_PASSWORD',
                'ELASTICSEARCH_API_KEY', 'QDRANT_API_KEY')) {
            $cloudValues[$key] = $localValues[$key]
        }
    }
    $cloudValues['MYSQL_DATABASE'] = 'ai_document_agent_cloud'
    $cloudValues['HYBRID_DATA_MODE'] = 'isolated'
    $cloudValues['REDIS_DATABASE'] = '1'
    $cloudValues['MYSQL_USER'] = 'ai_document_cloud'
    $cloudValues['MYSQL_PASSWORD'] = New-HybridRandomToken
    $cloudValues['RABBITMQ_USERNAME'] = 'ai_document_cloud'
    $cloudValues['RABBITMQ_PASSWORD'] = New-HybridRandomToken
    $cloudValues['RABBITMQ_VHOST'] = '/ai-document-agent-cloud'
    $cloudValues['QDRANT_COLLECTION'] = 'ai_document_chunks_cloud_v4'
    $cloudValues['QDRANT_INITIALIZATION_STRATEGY'] = 'CREATE_IF_MISSING'
    $cloudValues['KEYWORD_INDEX_NAME'] = 'document_chunks_cloud_v1'
    $cloudValues['KEYWORD_INDEX_ALIAS'] = 'document_chunks_cloud'
    $cloudValues['VECTOR_EMBEDDING_BATCH_SIZE'] = '10'
    $cloudValues['PROGRESS_TOKEN_FILE'] = './secrets/hybrid_progress_subscription_token'
    $cloudValues['DOCUMENT_OCR_ENABLED'] = 'true'
    $cloudValues['DOCUMENT_OCR_DPI'] = '150'
    Write-HybridEnvironmentFile -Path $cloudEnvPath -Values $cloudValues
    Write-Output 'Created private .env.hybrid.cloud with separate cloud database/message credentials.'
} else {
    Write-Output 'Kept existing .env.hybrid.cloud (including provider keys and passwords).'
}
if (-not (Test-Path -LiteralPath $secretPath)) {
    New-Item -ItemType Directory -Path $secretDirectory -Force | Out-Null
    Write-HybridUtf8File -Path $secretPath -Value (New-HybridRandomToken)
    Write-Output 'Created the separate cloud progress secret; value is not displayed.'
}

$cloudConfiguration = Get-HybridCloudConfiguration
$cloudValues = ConvertTo-HybridEnvironmentMap $cloudConfiguration.services.backend.environment
$database = $cloudValues['MYSQL_DATABASE']
$databaseUser = $cloudValues['MYSQL_USER']
$databasePassword = $cloudValues['MYSQL_PASSWORD']
$rabbitUser = $cloudValues['RABBITMQ_USERNAME']
$rabbitPassword = $cloudValues['RABBITMQ_PASSWORD']
$rabbitVhost = $cloudValues['RABBITMQ_VHOST']
$dataMode = if ($cloudValues['HYBRID_DATA_MODE']) { $cloudValues['HYBRID_DATA_MODE'] } else { 'isolated' }
if ($dataMode -notin @('isolated', 'original')) { throw 'Unknown HYBRID_DATA_MODE.' }
$originalVhost = if ($localValues['RABBITMQ_VHOST']) { $localValues['RABBITMQ_VHOST'] } else { '/' }
$originalCollection = if ($localValues['QDRANT_COLLECTION']) { $localValues['QDRANT_COLLECTION'] } else { 'ai_document_chunks_dashscope_v4' }
$originalAlias = if ($localValues['KEYWORD_INDEX_ALIAS']) { $localValues['KEYWORD_INDEX_ALIAS'] } else { 'document_chunks' }
$originalRedis = if ($localValues['SPRING_DATA_REDIS_DATABASE']) { $localValues['SPRING_DATA_REDIS_DATABASE'] } else { '0' }
if ($databaseUser -notmatch '^ai_document_cloud[a-z0-9_]*$' -or $databaseUser.Length -gt 32 -or
    $databasePassword -notmatch '^[A-Za-z0-9_-]{20,128}$' -or
    $rabbitUser -notmatch '^ai_document_cloud[a-z0-9_]*$' -or
    $rabbitPassword -notmatch '^[A-Za-z0-9_-]{20,128}$') {
    throw 'Keep initializer-generated cloud service users and passwords.'
}
if ($dataMode -eq 'isolated') {
    if ($database -notmatch '^ai_document_agent_cloud[a-z0-9_]*$' -or
        $rabbitVhost -notmatch '^/ai-document-agent-cloud[a-z0-9_-]*$') {
        throw 'Isolated mode requires the generated cloud database and vhost names.'
    }
    if ($database -eq $localValues['MYSQL_DATABASE'] -or $rabbitVhost -eq $originalVhost -or
        $originalRedis -eq $cloudValues['SPRING_DATA_REDIS_DATABASE'] -or
        $cloudValues['QDRANT_COLLECTION'] -eq $originalCollection -or
        $cloudValues['KEYWORD_INDEX_ALIAS'] -eq $originalAlias) {
        throw 'Cloud and local development namespaces must be different in isolated mode.'
    }
} else {
    if ($database -notmatch '^[a-zA-Z0-9_]+$' -or $database -ne $localValues['MYSQL_DATABASE'] -or
        $rabbitVhost -ne $originalVhost -or $cloudValues['QDRANT_COLLECTION'] -ne $originalCollection -or
        $cloudValues['KEYWORD_INDEX_ALIAS'] -ne $originalAlias -or
        $cloudValues['SPRING_DATA_REDIS_DATABASE'] -ne $originalRedis -or
        $cloudValues['HYBRID_DOCUMENT_VOLUME'] -ne 'ai-document-agent-cloud_original_document_data' -or
        $cloudValues['HYBRID_DOCUMENT_VOLUME_EXTERNAL'] -ne 'true' -or
        $cloudValues['QDRANT_INITIALIZATION_STRATEGY'] -ne 'VALIDATE_EXISTING') {
        throw 'Original-data mode must use the verified original namespaces and migrated ECS document volume. Changing the database name alone is insufficient.'
    }
    Assert-HybridSingleBackend $cloudConfiguration
}

if (-not $SkipStart) {
    Write-Output 'Starting Windows dependencies with loopback-only ports; named volumes and local app containers are retained.'
    Invoke-HybridDocker -Arguments ($composeArguments + @('up', '-d', '--no-deps', '--wait', '--wait-timeout', '180',
        'mysql', 'redis', 'rabbitmq', 'qdrant', 'elasticsearch')) | Out-Null
}

$databaseSetup = if ($dataMode -eq 'isolated') {
    "CREATE DATABASE IF NOT EXISTS $database CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
} else {
    # Refuse to silently create an empty replacement for the original database.
    "SELECT id FROM $database.knowledge_base LIMIT 1;"
}
$sql = @"
$databaseSetup
CREATE USER IF NOT EXISTS '$databaseUser'@'%' IDENTIFIED BY '$databasePassword';
GRANT ALL PRIVILEGES ON $database.* TO '$databaseUser'@'%';
"@
Invoke-HybridDocker -Arguments ($composeArguments + @('exec', '-T', 'mysql', 'sh', '-c',
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4')) -InputText $sql | Out-Null
Write-Output "Prepared $dataMode MySQL access: $database"

$vhosts = Invoke-HybridDocker -Arguments ($composeArguments + @('exec', '-T', 'rabbitmq', 'rabbitmqctl', '-q', 'list_vhosts', 'name'))
if ($rabbitVhost -notin @($vhosts.Trim() -split '\r?\n' | ForEach-Object { $_.Trim() })) {
    Invoke-HybridDocker -Arguments ($composeArguments + @('exec', '-T', 'rabbitmq', 'rabbitmqctl', 'add_vhost', $rabbitVhost)) | Out-Null
}
$users = Invoke-HybridDocker -Arguments ($composeArguments + @('exec', '-T', 'rabbitmq', 'rabbitmqctl', '-q', 'list_users'))
if ($users -notmatch ('(?m)^' + [regex]::Escape($rabbitUser) + '\s')) {
    Invoke-HybridDocker -Arguments ($composeArguments + @('exec', '-T', 'rabbitmq', 'rabbitmqctl', 'add_user', $rabbitUser, $rabbitPassword)) | Out-Null
}
Invoke-HybridDocker -Arguments ($composeArguments + @('exec', '-T', 'rabbitmq', 'rabbitmqctl', 'set_permissions',
    '-p', $rabbitVhost, $rabbitUser, '.*', '.*', '.*')) | Out-Null
Write-Output "Prepared $dataMode RabbitMQ access: $rabbitVhost"

try {
    Assert-HybridProviderKeys $cloudConfiguration.services.backend.environment
    Write-Output 'Chat/Embedding key fields are populated (provider authentication has not been tested).'
} catch {
    Write-Warning $_.Exception.Message
}
Write-Output 'Next: start-hybrid-tunnel.ps1 -CheckOnly, then publish-hybrid.ps1.'

param([int]$Port = 18081)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$configFile = Join-Path $projectRoot 'secrets/reading-v1-dev-db.json'
$config = Get-Content -LiteralPath $configFile -Raw | ConvertFrom-Json
if ($config.database -notmatch '^aida_reading_dev_[a-f0-9]{10}$' -or $config.url -notmatch '^jdbc:mysql://127\.0\.0\.1:3307/aida_reading_dev_[a-f0-9]{10}\?') {
    throw 'Only the isolated local reading development database is allowed.'
}
$jar = Join-Path $projectRoot 'target/ai-document-agent-0.0.1-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $jar)) { throw 'Build the backend JAR with Maven first.' }
$env:SPRING_DATASOURCE_URL = $config.url
$env:SPRING_DATASOURCE_USERNAME = $config.user
$env:SPRING_DATASOURCE_PASSWORD = $config.password
$env:SERVER_ADDRESS = '127.0.0.1'
$env:SERVER_PORT = [string]$Port
$env:INGESTION_ENABLED = 'false'
$env:AGENT_EXECUTION_ENABLED = 'false'
$env:PROGRESS_ENABLED = 'false'
$env:READING_ENABLED = 'true'
$env:READING_RERANK_ENABLED = 'false'
$env:VECTOR_EMBEDDING_MAX_RETRIES = '0'
# Existing provider credentials are resolved privately by application.yml's .env import.
Push-Location $projectRoot
$javaCommand = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java -ErrorAction Stop).Source }
try { & $javaCommand '-Xms128m' '-Xmx512m' -jar $jar }
finally { Pop-Location }

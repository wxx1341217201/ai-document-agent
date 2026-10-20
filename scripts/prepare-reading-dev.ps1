param([string]$ConfigPath = (Join-Path (Split-Path -Parent $PSScriptRoot) 'secrets/reading-v1-dev-db.json'))
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'hybrid-common.ps1')

# A separate schema contains the existing schema plus one explicitly selected book.
# Original application rows, queues and indexes are never changed by this script.
if (Test-Path -LiteralPath $ConfigPath) {
    $config = Get-Content -LiteralPath $ConfigPath -Raw | ConvertFrom-Json
    if ($config.database -notmatch '^aida_reading_dev_[a-f0-9]{10}$' -or $config.user -notmatch '^rv1d_[a-f0-9]{10}$') {
        throw 'The existing development configuration does not identify an isolated reading schema.'
    }
    Write-Output 'An isolated reading development database already exists. No changes made.'
    exit
}
$suffix = (New-HybridRandomToken).Substring(0, 10)
$database = 'aida_reading_dev_' + $suffix
$username = 'rv1d_' + $suffix
$password = New-HybridRandomToken
$sql = "CREATE DATABASE $database CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; CREATE USER '$username'@'%' IDENTIFIED BY '$password'; GRANT ALL PRIVILEGES ON $database.* TO '$username'@'%';"
$mysql = 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot --default-character-set=utf8mb4 --batch --skip-column-names'
Invoke-HybridDocker -Arguments @('exec', '-i', 'ai-agent-mysql', 'sh', '-c', $mysql) -InputText $sql | Out-Null
$config = [ordered]@{
    url = ('jdbc:mysql://127.0.0.1:3307/' + $database + '?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=3000&socketTimeout=5000')
    database = $database; user = $username; password = $password
}
Write-HybridUtf8File -Path $ConfigPath -Value ($config | ConvertTo-Json)
$clone = 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; set -o pipefail; mysqldump -uroot --no-data --no-tablespaces --set-gtid-purged=OFF --skip-add-drop-table ai_document_agent | mysql -uroot "$1"'
Invoke-HybridDocker -Arguments @('exec', 'ai-agent-mysql', 'bash', '-c', $clone, 'reading-clone', $database) | Out-Null
$copy = @"
INSERT INTO $database.flyway_schema_history SELECT * FROM ai_document_agent.flyway_schema_history;
INSERT INTO $database.knowledge_base SELECT * FROM ai_document_agent.knowledge_base WHERE id=8;
INSERT INTO $database.document SELECT * FROM ai_document_agent.document WHERE knowledge_base_id=8 AND id=15;
INSERT INTO $database.document_chunk SELECT * FROM ai_document_agent.document_chunk WHERE knowledge_base_id=8 AND document_id=15;
SELECT COUNT(*) AS copied_book_chunks FROM $database.document_chunk;
"@
$count = Invoke-HybridDocker -Arguments @('exec', '-i', 'ai-agent-mysql', 'sh', '-c', $mysql) -InputText $copy
Write-Output ('Isolated reading development database prepared. Copied book chunks: ' + $count.Trim())

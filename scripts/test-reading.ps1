param([switch]$AllTests)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'hybrid-common.ps1')
$suffix = (New-HybridRandomToken).Substring(0, 10)
$database = 'aida_reading_test_' + $suffix
$username = 'rv1t_' + $suffix
$password = New-HybridRandomToken
$configPath = Join-Path $script:HybridProjectRoot ("secrets/reading-test-$suffix.json")
$previousConfig = $env:READING_TEST_CONFIG
$mysql = 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot --default-character-set=utf8mb4 --batch --skip-column-names'
$created = $false
$testExit = 1
try {
    $sql = "CREATE DATABASE $database CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; CREATE USER '$username'@'%' IDENTIFIED BY '$password'; GRANT ALL PRIVILEGES ON $database.* TO '$username'@'%';"
    Invoke-HybridDocker -Arguments @('exec', '-i', 'ai-agent-mysql', 'sh', '-c', $mysql) -InputText $sql | Out-Null
    $created = $true
    $config = [ordered]@{
        url = ('jdbc:mysql://127.0.0.1:3307/' + $database + '?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true')
        database = $database; user = $username; password = $password
    }
    Write-HybridUtf8File -Path $configPath -Value ($config | ConvertTo-Json)
    $env:READING_TEST_CONFIG = $configPath
    Push-Location $script:HybridProjectRoot
    try {
        if ($AllTests) { & mvn -q test } else { & mvn -q '-Dtest=Reading*Test' test }
        $testExit = $LASTEXITCODE
    } finally { Pop-Location }
}
finally {
    $env:READING_TEST_CONFIG = $previousConfig
    if ($created) {
        if ($database -cnotmatch '^aida_reading_test_[a-f0-9]{10}$' -or $username -cne ('rv1t_' + $suffix) -or
                $database -cne ('aida_reading_test_' + $suffix)) { throw 'Refusing cleanup of an unexpected database or user.' }
        $drop = "DROP DATABASE $database; DROP USER '$username'@'%';"
        Invoke-HybridDocker -Arguments @('exec', '-i', 'ai-agent-mysql', 'sh', '-c', $mysql) -InputText $drop | Out-Null
        if (Test-Path -LiteralPath $configPath) { Remove-Item -LiteralPath $configPath }
    }
}
exit $testExit

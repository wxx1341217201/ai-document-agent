[CmdletBinding()]
param(
    [ValidatePattern('^(?!-)[A-Za-z0-9_.@-]+$')][string]$SshHost = 'aliyun',
    [switch]$CheckOnly,
    [switch]$Once,
    [switch]$Background,
    [switch]$Stop
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'hybrid-common.ps1')

$stateDirectory = Join-Path $script:HybridProjectRoot 'artifacts/hybrid'
$statePath = Join-Path $stateDirectory 'tunnel-process.json'
if (Test-Path -LiteralPath $statePath) {
    $savedWorker = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
    $existingWorker = Get-Process -Id ([int]$savedWorker.processId) -ErrorAction SilentlyContinue
    if ($existingWorker -and ($existingWorker.ProcessName -notin @('pwsh', 'powershell') -or
        $existingWorker.StartTime.ToUniversalTime().Ticks -ne [long]$savedWorker.startTimeTicks)) {
        throw 'The saved tunnel PID belongs to a different process. No process was stopped.'
    }
} else {
    $existingWorker = $null
}
if ($Stop) {
    if ($existingWorker) {
        $sshChildren = @(Get-CimInstance Win32_Process -Filter "ParentProcessId = $($existingWorker.Id)" |
            Where-Object { $_.Name -eq 'ssh.exe' })
        Stop-Process -Id $existingWorker.Id
        foreach ($child in $sshChildren) { Stop-Process -Id $child.ProcessId -ErrorAction SilentlyContinue }
        Write-Output 'Stopped the managed hybrid tunnel.'
    } else {
        Write-Output 'No managed hybrid tunnel is running.'
    }
    if (Test-Path -LiteralPath $statePath) { Remove-Item -LiteralPath $statePath }
    return
}
if ($Background -and $existingWorker) {
    Write-Output "Managed tunnel is already running (PID $($existingWorker.Id))."
    return
}

foreach ($mapping in (Get-HybridTunnelMappings)) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $pending = $client.BeginConnect('127.0.0.1', $mapping.LocalPort, $null, $null)
        if (-not $pending.AsyncWaitHandle.WaitOne(3000)) { throw 'connection timeout' }
        $client.EndConnect($pending)
        Write-Output "$($mapping.Service): local port $($mapping.LocalPort) reachable"
    } catch {
        throw "$($mapping.Service) is not reachable at 127.0.0.1:$($mapping.LocalPort). Run initialize-hybrid.ps1 first."
    } finally {
        $client.Dispose()
    }
}

& ssh -T -o BatchMode=yes -o StrictHostKeyChecking=yes -o ConnectTimeout=10 $SshHost 'true'
if ($LASTEXITCODE -ne 0) {
    throw "SSH authentication to '$SshHost' failed. Verify ssh $SshHost before opening the tunnel."
}
if ($CheckOnly) {
    Write-Output 'Local dependency ports and SSH authentication passed. No tunnel was started.'
    return
}

if ($Background) {
    New-Item -ItemType Directory -Path $stateDirectory -Force | Out-Null
    $shellExecutable = if ($PSVersionTable.PSVersion.Major -ge 6) {
        Join-Path $PSHOME 'pwsh.exe'
    } else { Join-Path $PSHOME 'powershell.exe' }
    $workerOptions = @{
        FilePath = $shellExecutable
        ArgumentList = @('-NoProfile', '-File', ('"' + $PSCommandPath + '"'), '-SshHost', $SshHost)
        WindowStyle = 'Hidden'
        PassThru = $true
        RedirectStandardOutput = (Join-Path $stateDirectory 'tunnel.stdout.log')
        RedirectStandardError = (Join-Path $stateDirectory 'tunnel.stderr.log')
    }
    $worker = Start-Process @workerOptions
    $workerState = [ordered]@{
        processId = $worker.Id
        startTimeTicks = $worker.StartTime.ToUniversalTime().Ticks
        sshHost = $SshHost
    }
    Write-HybridUtf8File -Path $statePath -Value ($workerState | ConvertTo-Json)
    Write-Output "Started the background tunnel worker (PID $($worker.Id)). Logs: artifacts/hybrid/tunnel.*.log"
    return
}

$sshArguments = Get-HybridTunnelArguments -SshHost $SshHost
Write-Output 'Opening encrypted reverse forwards, bound only to ECS loopback.'
Write-Output 'Cloud UI after deployment: http://127.0.0.1:18088 . Keep this terminal open; Ctrl+C stops the tunnel.'
do {
    & ssh @sshArguments
    $sshExitCode = $LASTEXITCODE
    if ($Once) {
        if ($sshExitCode -ne 0) { throw "SSH tunnel stopped (exit $sshExitCode)." }
        break
    }
    Write-Warning "Tunnel stopped (exit $sshExitCode); reconnecting in 5 seconds."
    Start-Sleep -Seconds 5
} while ($true)

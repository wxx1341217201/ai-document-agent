param(
    [ValidateSet('EXECUTION', 'DOCUMENT')][string]$Resource = 'EXECUTION',
    [Parameter(Mandatory = $true)][long]$KnowledgeBaseId,
    [string]$ExecutionId,
    [long]$DocumentId,
    [string]$Url = 'ws://localhost:8080/ws/progress',
    [int]$Seconds = 60
)

$ErrorActionPreference = 'Stop'
if ($Resource -eq 'EXECUTION' -and [string]::IsNullOrWhiteSpace($ExecutionId)) {
    throw 'EXECUTION requires -ExecutionId.'
}
if ($Resource -eq 'DOCUMENT' -and $DocumentId -le 0) {
    throw 'DOCUMENT requires -DocumentId.'
}

$socket = [System.Net.WebSockets.ClientWebSocket]::new()
if (-not [string]::IsNullOrWhiteSpace($env:PROGRESS_SUBSCRIPTION_TOKEN)) {
    $socket.Options.SetRequestHeader('Authorization', 'Bearer ' + $env:PROGRESS_SUBSCRIPTION_TOKEN)
}
$cancel = [System.Threading.CancellationTokenSource]::new([TimeSpan]::FromSeconds($Seconds))
try {
    $socket.ConnectAsync([Uri]$Url, $cancel.Token).GetAwaiter().GetResult()
    $request = @{ action = 'SUBSCRIBE'; resource = $Resource; knowledgeBaseId = $KnowledgeBaseId }
    if ($Resource -eq 'EXECUTION') { $request.executionId = $ExecutionId }
    else { $request.documentId = $DocumentId }
    $bytes = [Text.Encoding]::UTF8.GetBytes(($request | ConvertTo-Json -Compress))
    $socket.SendAsync([ArraySegment[byte]]::new($bytes),
        [System.Net.WebSockets.WebSocketMessageType]::Text, $true, $cancel.Token).GetAwaiter().GetResult()

    $buffer = [byte[]]::new(8192)
    while ($socket.State -eq [System.Net.WebSockets.WebSocketState]::Open -and -not $cancel.IsCancellationRequested) {
        $message = [System.IO.MemoryStream]::new()
        do {
            $part = $socket.ReceiveAsync([ArraySegment[byte]]::new($buffer), $cancel.Token).GetAwaiter().GetResult()
            if ($part.MessageType -eq [System.Net.WebSockets.WebSocketMessageType]::Close) { return }
            $message.Write($buffer, 0, $part.Count)
        } while (-not $part.EndOfMessage)
        $json = [Text.Encoding]::UTF8.GetString($message.ToArray())
        $json
        $event = $json | ConvertFrom-Json
        if ($event.type -eq 'SUBSCRIBED') {
            Write-Host "REST status: $($event.statusUrl); latest sequence: $($event.latestSequence)"
        }
        if ($event.type -in @('DOCUMENT_READY', 'DOCUMENT_FAILED', 'EXECUTION_COMPLETED', 'EXECUTION_FAILED')) {
            Write-Host 'Terminal notice received. Query the REST status URL from SUBSCRIBED for the committed result.'
            break
        }
    }
}
finally {
    if ($socket.State -eq [System.Net.WebSockets.WebSocketState]::Open) {
        $socket.CloseAsync([System.Net.WebSockets.WebSocketCloseStatus]::NormalClosure,
            'done', [System.Threading.CancellationToken]::None).GetAwaiter().GetResult()
    }
    $socket.Dispose()
    $cancel.Dispose()
}

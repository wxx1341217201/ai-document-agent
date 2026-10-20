[CmdletBinding()]
param([Parameter(Mandatory)][string]$ReportDirectory)
$ErrorActionPreference = 'Stop'
$taskRepo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$taskReport = (Resolve-Path -LiteralPath $ReportDirectory).Path
if (-not $taskReport.StartsWith($taskRepo + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Audit output must be inside this workspace.'
}
$taskSummary = Get-Content -LiteralPath (Join-Path $taskReport 'summary.json') -Raw | ConvertFrom-Json
$taskManifest = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'data/manifest.json') -Raw | ConvertFrom-Json
$taskIssues = New-Object 'System.Collections.Generic.List[string]'
$taskChecks = New-Object 'System.Collections.Generic.List[object]'
$taskFiles = @{}
function Check([bool]$Condition, [string]$Message) { if (-not $Condition) { $taskIssues.Add($Message) } }
function Near([double]$Left, [double]$Right) { return [Math]::Abs($Left - $Right) -lt 0.00001 }
function CheckDistribution([double[]]$Values, $Distribution, [string]$Message) {
    Check ($Values.Count -eq $Distribution.samples) ($Message + ': sample count differs')
    if ($Values.Count -eq 0) { $taskIssues.Add($Message + ': empty distribution'); return }
    $taskSorted = @($Values | Sort-Object)
    $taskMean = ($Values | Measure-Object -Average).Average
    $taskP95 = $taskSorted[[Math]::Ceiling($Values.Count * 0.95) - 1]
    Check (Near $taskMean $Distribution.mean) ($Message + ': mean differs')
    Check (Near $taskP95 $Distribution.p95) ($Message + ': nearest-rank P95 differs')
}
Check ($taskManifest.documents.Count -eq 100) 'Corpus must contain 100 documents.'
foreach ($taskEntry in $taskManifest.documents) {
    $taskFile = Join-Path $PSScriptRoot ('data/' + $taskEntry.file)
    Check ((Get-FileHash -LiteralPath $taskFile -Algorithm SHA256).Hash.ToLowerInvariant() -eq $taskEntry.sha256) ('Corpus SHA mismatch: ' + $taskEntry.id)
    $taskFiles[$taskEntry.id] = $taskEntry
}
foreach ($taskFingerprint in $taskSummary.plan.fingerprints.PSObject.Properties) {
    $taskPath = [IO.Path]::GetFullPath((Join-Path $taskRepo $taskFingerprint.Name))
    Check ($taskPath.StartsWith($taskRepo + '\', [StringComparison]::OrdinalIgnoreCase)) ('Fingerprint path escaped workspace: ' + $taskFingerprint.Name)
    if (-not (Test-Path -LiteralPath $taskPath -PathType Leaf)) { $taskIssues.Add('Missing fingerprint file: ' + $taskFingerprint.Name); continue }
    Check ((Get-FileHash -LiteralPath $taskPath -Algorithm SHA256).Hash.ToLowerInvariant() -eq $taskFingerprint.Value) ('Source/binary changed: ' + $taskFingerprint.Name)
}
Check ($taskSummary.rows.Count -eq 15) 'Formal matrix must contain 15 rows.'
Check ($taskSummary.comparison.formalMatrixComplete) 'Formal matrix did not pass the evaluator.'
$taskExpectedCases = @{}
foreach ($taskBatchSize in @(25, 50, 100)) {
    $taskExpectedCases['sync-b' + $taskBatchSize + '-c1'] = $false
    foreach ($taskConsumer in @(1, 2, 4, 8)) { $taskExpectedCases['async-b' + $taskBatchSize + '-c' + $taskConsumer] = $false }
}
$taskPeaks = @{}
$taskEnvironmentIds = @{}
$taskTotalDocs = 0
$taskTotalChunks = 0
$taskImageIdentity = $null
foreach ($taskRow in $taskSummary.rows) {
    $taskId = '{0}-b{1}-c{2}' -f $taskRow.config.mode, $taskRow.config.batchSize, $taskRow.config.consumers
    Check ($taskExpectedCases.ContainsKey($taskId)) ($taskId + ': unexpected matrix configuration')
    Check (-not $taskExpectedCases[$taskId]) ($taskId + ': duplicate matrix configuration')
    $taskExpectedCases[$taskId] = $true
    Check ($taskRow.evaluation.valid) ($taskId + ': evaluator rejected the data')
    Check ($taskRow.cleanup -eq 'COMPLETED') ($taskId + ': cleanup failed')
    Check (-not $taskEnvironmentIds.ContainsKey($taskRow.environment.runId)) ($taskId + ': reused environment')
    $taskEnvironmentIds[$taskRow.environment.runId] = $true
    $taskImagesFile = Join-Path $taskRow.environment.runtimeDirectory 'image-identities.log'
    if (Test-Path -LiteralPath $taskImagesFile) {
        $taskCurrentImages = (Get-Content -LiteralPath $taskImagesFile -Raw).Trim()
        if ($null -eq $taskImageIdentity) { $taskImageIdentity = $taskCurrentImages }
        Check ($taskCurrentImages -eq $taskImageIdentity) ($taskId + ': Docker image identities differ')
    } else { $taskIssues.Add($taskId + ': missing Docker image identity evidence') }
    $taskRawFile = Join-Path $taskReport ($taskId + '.json')
    if (-not (Test-Path -LiteralPath $taskRawFile)) { $taskIssues.Add($taskId + ': missing raw JSON'); continue }
    $taskRaw = Get-Content -LiteralPath $taskRawFile -Raw | ConvertFrom-Json
    Check ($taskRaw.clientObservations.Count -eq $taskRaw.documents.Count) ($taskId + ': client/document count differs')
    Check (@($taskRaw.clientObservations | Where-Object { -not $_.success }).Count -eq 0) ($taskId + ': client failure')
    Check (@($taskRaw.documents.corpusId | Sort-Object -Unique).Count -eq 100) ($taskId + ': incomplete 100-document corpus')
    Check ($taskRaw.verification.incompleteIndexes -eq 0 -and $taskRaw.verification.mismatchedIndexes -eq 0) ($taskId + ': incomplete or incorrect index')
    Check ($taskRaw.verification.pendingDocuments -eq 0 -and $taskRaw.verification.failedDocuments -eq 0) ($taskId + ': undrained/failed document')
    Check ($taskRaw.verification.transactionIsolation -eq 'REPEATABLE-READ') ($taskId + ': isolation mismatch')
    $taskVectors = @{}
    $taskKeywords = @{}
    $taskBatchesPerDoc = @{}
    $taskAckValues = New-Object 'System.Collections.Generic.List[double]'
    $taskE2eValues = New-Object 'System.Collections.Generic.List[double]'
    $taskQueueValues = New-Object 'System.Collections.Generic.List[double]'
    $taskProcessingValues = New-Object 'System.Collections.Generic.List[double]'
    foreach ($taskStage in $taskRaw.stages) {
        Check ($null -ne $taskStage.enqueuedAt -and $null -ne $taskStage.startedAt -and $null -ne $taskStage.completedAt) ($taskId + ': missing stage time')
        Check ($taskStage.startedAt -ge $taskStage.enqueuedAt -and $taskStage.completedAt -ge $taskStage.startedAt) ($taskId + ': invalid stage chronology')
        if ($taskStage.stage -eq 'VECTOR_INDEX') {
            Check (-not $taskVectors.ContainsKey($taskStage.batchId)) ($taskId + ': duplicate vector stage')
            $taskVectors[$taskStage.batchId] = $taskStage
            $taskDocKey = [string]$taskStage.documentId
            if (-not $taskBatchesPerDoc.ContainsKey($taskDocKey)) { $taskBatchesPerDoc[$taskDocKey] = 0 }
            $taskBatchesPerDoc[$taskDocKey]++
        } else {
            Check ($taskStage.stage -eq 'KEYWORD_INDEX') ($taskId + ': unexpected stage')
            Check (-not $taskKeywords.ContainsKey($taskStage.batchId)) ($taskId + ': duplicate keyword stage')
            $taskKeywords[$taskStage.batchId] = $taskStage
        }
    }
    Check ($taskVectors.Count -eq $taskKeywords.Count) ($taskId + ': stage counts differ')
    foreach ($taskBatchId in $taskVectors.Keys) {
        $taskVector = $taskVectors[$taskBatchId]
        $taskKeyword = $taskKeywords[$taskBatchId]
        Check ($null -ne $taskKeyword) ($taskId + ': keyword stage missing for ' + $taskBatchId)
        Check ($taskVector.documentId -eq $taskKeyword.documentId -and $taskVector.chunks -eq $taskKeyword.chunks) ($taskId + ': stage identity differs')
        Check ($taskKeyword.startedAt -ge $taskVector.completedAt) ($taskId + ': keyword ran before vector completion')
        $taskQueueValues.Add($taskVector.startedAt - $taskVector.enqueuedAt)
        $taskProcessingValues.Add($taskKeyword.completedAt - $taskVector.startedAt)
    }
    $taskChunks = 0
    $taskAckSum = 0.0
    foreach ($taskDoc in $taskRaw.documents) {
        $taskExpected = $taskFiles[$taskDoc.corpusId]
        Check ($null -ne $taskExpected) ($taskId + ': unknown corpus document')
        Check ($taskDoc.bytes -eq $taskExpected.bytes) ($taskId + ': file size differs from corpus')
        $taskDocKey = [string]$taskDoc.documentId
        $taskActualChunks = $taskRaw.actualChunksPerDocument.$taskDocKey
        Check ($taskActualChunks -eq $taskExpected.expectedChunks) ($taskId + ': actual chunk count differs')
        Check ($taskBatchesPerDoc[$taskDocKey] -eq [Math]::Ceiling($taskExpected.expectedChunks / [double]$taskRow.config.batchSize)) ($taskId + ': batch split differs')
        Check ($taskDoc.ackAt -ge $taskDoc.arrivedAt -and $taskDoc.readyAt -ge $taskDoc.arrivedAt) ($taskId + ': invalid document time')
        $taskChunks += $taskActualChunks
        $taskAckSum += $taskDoc.ackAt - $taskDoc.arrivedAt
        $taskAckValues.Add($taskDoc.ackAt - $taskDoc.arrivedAt)
        $taskE2eValues.Add($taskDoc.readyAt - $taskDoc.arrivedAt)
    }
    $taskWarmupChunks = 0
    for ($taskIndex = 0; $taskIndex -lt $taskSummary.plan.options.warmupDocuments; $taskIndex++) {
        $taskWarmupChunks += $taskManifest.documents[$taskIndex].expectedChunks
    }
    Check ($taskRaw.verification.persistedChunks -eq ($taskChunks + $taskWarmupChunks)) ($taskId + ': persistent chunk total differs')
    Check ($taskRaw.verification.indexedChunks -eq $taskRaw.verification.persistedChunks) ($taskId + ': index row count differs')
    Check (Near ($taskAckSum / $taskRaw.documents.Count) $taskRow.evaluation.submitAckLatencyMs.mean) ($taskId + ': ACK mean differs')
    CheckDistribution ($taskAckValues.ToArray()) $taskRow.evaluation.submitAckLatencyMs ($taskId + ': ACK')
    CheckDistribution ($taskE2eValues.ToArray()) $taskRow.evaluation.endToEndLatencyMs ($taskId + ': E2E')
    CheckDistribution ($taskQueueValues.ToArray()) $taskRow.evaluation.queueWaitLatencyMs ($taskId + ': queue wait')
    CheckDistribution ($taskProcessingValues.ToArray()) $taskRow.evaluation.processingLatencyMs ($taskId + ': processing')
    $taskPeak = $taskRow.evaluation.peakStableWindow
    if ($null -eq $taskPeak) { $taskIssues.Add($taskId + ': no stable window'); continue }
    Check (Near ($taskPeak.to - $taskPeak.from) 60000) ($taskId + ': window is not 60 seconds')
    Check ($taskPeak.from -ge $taskRaw.measurementStartedAt -and $taskPeak.to -le $taskRaw.admissionStoppedAt) ($taskId + ': window includes warmup/drain')
    $taskCompletedDocs = @($taskRaw.documents | Where-Object { $_.readyAt -ge $taskPeak.from -and $_.readyAt -lt $taskPeak.to }).Count
    $taskCompletedBatches = @($taskKeywords.Values | Where-Object { $_.completedAt -ge $taskPeak.from -and $_.completedAt -lt $taskPeak.to }).Count
    Check (Near ($taskCompletedDocs / 60.0) $taskPeak.documentsPerSecond) ($taskId + ': completed doc throughput differs')
    Check (Near ($taskCompletedBatches / 60.0) $taskPeak.batchesPerSecond) ($taskId + ': logical batch throughput differs')
    $taskBinMean = $taskCompletedDocs / 4.0
    $taskBinVariance = 0.0
    for ($taskBin = 0; $taskBin -lt 4; $taskBin++) {
        $taskBinStart = $taskPeak.from + $taskBin * 15000
        $taskBinEnd = $taskBinStart + 15000
        $taskBinCount = @($taskRaw.documents | Where-Object { $_.readyAt -ge $taskBinStart -and $_.readyAt -lt $taskBinEnd }).Count
        $taskBinVariance += [Math]::Pow($taskBinCount - $taskBinMean, 2) / 4.0
    }
    Check (Near ([Math]::Sqrt($taskBinVariance) / $taskBinMean) $taskPeak.completionBinCv) ($taskId + ': completion CV differs')
    $taskFinishedClients = @($taskRaw.clientObservations | Where-Object { $_.finishedAt -ge $taskPeak.from -and $_.finishedAt -lt $taskPeak.to })
    $taskWindowErrors = @($taskFinishedClients | Where-Object { -not $_.success }).Count
    Check ($taskFinishedClients.Count -gt 0) ($taskId + ': stable window has no client completions')
    if ($taskFinishedClients.Count -gt 0) { Check (Near ($taskWindowErrors / [double]$taskFinishedClients.Count) $taskPeak.errorRate) ($taskId + ': window error rate differs') }
    $taskFirstSample = @($taskRaw.samples | Where-Object { $_.at -eq $taskPeak.from }) | Select-Object -First 1
    $taskLastSample = @($taskRaw.samples | Where-Object { $_.at -ge $taskPeak.to }) | Select-Object -First 1
    Check ($null -ne $taskFirstSample -and $null -ne $taskLastSample) ($taskId + ': stable window sample endpoints missing')
    if ($null -ne $taskFirstSample -and $null -ne $taskLastSample) {
        Check (($taskLastSample.batchBacklog - $taskFirstSample.batchBacklog) -eq $taskPeak.batchBacklogGrowth) ($taskId + ': backlog growth differs')
        $taskWindowSamples = @($taskRaw.samples | Where-Object { $_.at -ge $taskPeak.from -and $_.at -le $taskLastSample.at })
        Check (($taskLastSample.at - $taskPeak.to) -le 2500) ($taskId + ': final sample gap exceeds limit')
        for ($taskSampleIndex = 1; $taskSampleIndex -lt $taskWindowSamples.Count; $taskSampleIndex++) {
            Check (($taskWindowSamples[$taskSampleIndex].at - $taskWindowSamples[$taskSampleIndex - 1].at) -le 2500) ($taskId + ': sample gap exceeds limit')
        }
    }
    $taskStableWindows = @($taskRow.evaluation.windows | Where-Object { $_.stable })
    $taskMax = ($taskStableWindows.documentsPerSecond | Measure-Object -Maximum).Maximum
    Check (Near $taskMax $taskPeak.documentsPerSecond) ($taskId + ': selected peak differs from stable maximum')
    Check ($taskPeak.errorRate -le $taskSummary.plan.options.errorThreshold -and $taskPeak.completionBinCv -le 0.35) ($taskId + ': stability criteria failed')
    foreach ($taskSuffix in @('documents','batches','stages','clients','windows','resources')) {
        Check (Test-Path -LiteralPath (Join-Path $taskReport ($taskId + '-' + $taskSuffix + '.csv'))) ($taskId + ': missing ' + $taskSuffix + ' CSV')
    }
    $taskPeaks[$taskId] = [double]$taskPeak.documentsPerSecond
    $taskTotalDocs += $taskRaw.documents.Count
    $taskTotalChunks += $taskChunks
    $taskChecks.Add([pscustomobject]@{config=$taskId; documents=$taskRaw.documents.Count; logicalBatches=$taskKeywords.Count; chunks=$taskChunks; peakDocsPerSecond=$taskPeak.documentsPerSecond; submitAckMeanMs=$taskAckSum/$taskRaw.documents.Count; environmentRunId=$taskRow.environment.runId; dockerImages=$taskCurrentImages; cleanup=$taskRow.cleanup; rawSha256=(Get-FileHash -LiteralPath $taskRawFile -Algorithm SHA256).Hash.ToLowerInvariant()})
}
foreach ($taskCase in $taskExpectedCases.Keys) { Check $taskExpectedCases[$taskCase] ('Missing matrix configuration: ' + $taskCase) }
$taskSyncPeak = ($taskPeaks.GetEnumerator() | Where-Object { $_.Key.StartsWith('sync-') } | ForEach-Object Value | Measure-Object -Maximum).Maximum
$taskAsyncPeak = ($taskPeaks.GetEnumerator() | Where-Object { $_.Key.StartsWith('async-') } | ForEach-Object Value | Measure-Object -Maximum).Maximum
$taskGain = if ($taskSyncPeak -gt 0) { $taskAsyncPeak / $taskSyncPeak } else { $null }
if ($null -ne $taskGain) { Check (Near $taskGain $taskSummary.comparison.throughputGain) 'Global throughput ratio differs.' }
$taskAudit = [ordered]@{schema='m19e-independent-audit-v1'; runId=$taskSummary.plan.runId; auditedAt=[DateTime]::UtcNow.ToString('o'); status=if ($taskIssues.Count -eq 0) {'PASSED'} else {'FAILED'}; rows=$taskChecks; measuredDocuments=$taskTotalDocs; measuredChunks=$taskTotalChunks; throughputGain=$taskGain; issues=$taskIssues; summarySha256=(Get-FileHash -LiteralPath (Join-Path $taskReport 'summary.json') -Algorithm SHA256).Hash.ToLowerInvariant()}
$taskJson = $taskAudit | ConvertTo-Json -Depth 8
[IO.File]::WriteAllText((Join-Path $taskReport 'audit.json'), $taskJson, (New-Object Text.UTF8Encoding($false)))
if ($taskIssues.Count -gt 0) { throw ($taskIssues -join [Environment]::NewLine) }
Write-Output "Audit PASSED: 15 rows, $taskTotalDocs documents, $taskTotalChunks chunks, throughput gain=$taskGain"

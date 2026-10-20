# M18 progress WebSocket

Endpoint: `ws://localhost:8080/ws/progress`. Set `PROGRESS_SUBSCRIPTION_TOKEN` in the server environment. Without it, the default authorizer rejects every subscription. The same value is sent in an `Authorization: Bearer ...` handshake header. A future user system can replace the `ProgressSubscriptionAuthorizer` bean; the default token grants access to all knowledge bases, so use a trusted deployment token.

Send one of these JSON messages after connecting:

```json
{"action":"SUBSCRIBE","resource":"EXECUTION","knowledgeBaseId":7,"executionId":"12345678-1234-1234-1234-123456789abc"}
```

```json
{"action":"SUBSCRIBE","resource":"DOCUMENT","knowledgeBaseId":7,"documentId":12}
```

The server sends `CONNECTED`, then `SUBSCRIBED` with `executionId`, `latestSequence`, and `statusUrl`. A progress event may arrive just before `SUBSCRIBED` when a worker finishes during subscription. For documents, `executionId` is the ingestion job UUID. A denied request receives `SUBSCRIPTION_REJECTED` with a safe code such as `FORBIDDEN` or `NOT_FOUND`. An `UNSUBSCRIBE` message uses the same fields.

PowerShell test (PowerShell 7 or newer):

```powershell
$env:PROGRESS_SUBSCRIPTION_TOKEN = '<deployment token from your environment>'
.\scripts\test-progress-websocket.ps1 -Resource EXECUTION -KnowledgeBaseId 7 -ExecutionId '12345678-1234-1234-1234-123456789abc'
.\scripts\test-progress-websocket.ps1 -Resource DOCUMENT -KnowledgeBaseId 7 -DocumentId 12
```

For a denial check, unset `PROGRESS_SUBSCRIPTION_TOKEN` in the client shell and run the same script; it will receive `FORBIDDEN`. Each stream sequence starts at 1 and is stored in MySQL. Clients should compare incoming sequence values and fetch `statusUrl` on a gap, after reconnect, or on a terminal notice. There is no event replay. `PARSING` and `CHUNKING` are live phase signals and may arrive before the processing transaction commits; the REST response is the authoritative state.

The sender has a bounded per-session queue and a bounded worker queue. A slow connection is closed with WebSocket code 1013. Delivery is best effort and local to the application instance; multi-instance fanout is not implemented in M18.

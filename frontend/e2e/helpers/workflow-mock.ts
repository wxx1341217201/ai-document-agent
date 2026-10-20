import { expect, type Page, type WebSocketRoute } from '@playwright/test';
import type {
  Conversation,
  ConversationMessage,
  DocumentChunk,
  DocumentRecord,
  ExecutionResponse,
  KnowledgeBase,
  RagCitation,
} from '../../src/api/contracts';
import type { ProgressEvent, ProgressSubscription } from '../../src/api/progress.contracts';
import { mockPlanExecution, completeMockPlan } from '../../src/test/mocks/agent-panel-fixtures';

/** Node-owned REST/WebSocket Mock state survives a complete browser reload.
 * This is frontend acceptance coverage, not a real RAG/model/backend test.
 */
export async function mockWorkflowBackend(page: Page) {
  const timestamp = '2026-10-11T08:30:00';
  const knowledgeBases: KnowledgeBase[] = [];
  const documents: DocumentRecord[] = [];
  const files = new Map<number, Buffer>();
  const chunks = new Map<number, DocumentChunk[]>();
  const conversations: Conversation[] = [];
  const messages = new Map<number, ConversationMessage[]>();
  const executions = new Map<string, ExecutionResponse>();
  const subscriptions = new Map<string, ProgressSubscription>();
  const sockets = new Map<string, WebSocketRoute>();
  const sequences = new Map<string, number>();
  const posts = { resume: 0, cancel: 0, execution: 0 };
  let resumeConflict = false;
  let messageId = 1;
  let chunkId = 997;
  const streamId = (target: ProgressSubscription) =>
    target.resource === 'EXECUTION'
      ? target.executionId
      : `22222222-2222-4222-8222-${String(target.documentId).padStart(12, '0')}`;
  function publish(
    target: ProgressSubscription,
    type: ProgressEvent['type'],
    summary: string,
    status: string,
  ) {
    const id = streamId(target);
    const sequence = (sequences.get(id) ?? 0) + 1;
    sequences.set(id, sequence);
    const event: ProgressEvent = {
      eventId: crypto.randomUUID(),
      executionId: id,
      sequence,
      type,
      timestamp,
      payload: {
        stepId: target.resource === 'EXECUTION' ? 'r0_S2' : null,
        toolName: null,
        documentId: target.resource === 'DOCUMENT' ? target.documentId : null,
        errorCode: null,
        summary,
        status,
      },
    };
    for (const [key, subscription] of subscriptions) {
      if (streamId(subscription) === id) sockets.get(key)?.send(JSON.stringify(event));
    }
  }
  await page.routeWebSocket('**/ws/progress', (socket) => {
    socket.onClose(() => {
      for (const [key, current] of sockets) {
        if (current === socket) {
          sockets.delete(key);
          subscriptions.delete(key);
        }
      }
    });
    socket.onMessage((raw) => {
      const command = JSON.parse(String(raw)) as ProgressSubscription & { action: string };
      const key = command.resource + ':' + streamId(command);
      if (command.action === 'SUBSCRIBE') {
        expect(command.knowledgeBaseId).toBe(1);
        subscriptions.set(key, command);
        sockets.set(key, socket);
        socket.send(
          JSON.stringify({
            type: 'SUBSCRIBED',
            resource: command.resource,
            knowledgeBaseId: command.knowledgeBaseId,
            executionId: streamId(command),
            documentId: command.resource === 'DOCUMENT' ? command.documentId : null,
            latestSequence: sequences.get(streamId(command)) ?? 0,
          }),
        );
      } else {
        subscriptions.delete(key);
        sockets.delete(key);
        socket.send(JSON.stringify({ type: 'UNSUBSCRIBED' }));
      }
    });
  });

  function paginate<T>(content: T[], url: URL) {
    const pageNumber = Number(url.searchParams.get('page') ?? 0);
    const size = Number(url.searchParams.get('size') ?? 20);
    return {
      content: content.slice(pageNumber * size, (pageNumber + 1) * size),
      page: pageNumber,
      size,
      totalElements: content.length,
      totalPages: Math.ceil(content.length / size),
    };
  }
  function citation(): RagCitation {
    const document = documents[0];
    const chunk = chunks.get(document.id)![0];
    return {
      citationId: 'C1',
      documentId: document.id,
      documentName: document.originalName,
      chunkId: chunk.chunkId,
      pageFrom: chunk.pageFrom,
      pageTo: chunk.pageTo,
      quote: chunk.content,
    };
  }
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    const method = request.method();
    let data: unknown;
    let status = 200;
    let code = 'SUCCESS';
    let message = 'OK';
    if (path === '/api/v1/knowledge-bases') {
      if (method === 'POST') {
        const input = request.postDataJSON();
        const kb = {
          id: knowledgeBases.length + 1,
          name: input.name,
          description: input.description ?? null,
          createdAt: timestamp,
          updatedAt: timestamp,
        };
        knowledgeBases.push(kb);
        data = kb;
        status = 201;
      } else data = paginate(knowledgeBases, url);
    } else if (path === '/api/v1/system/health')
      data = {
        status: 'UP',
        components: {
          database: 'UP',
          redis: 'UP',
          rabbitmq: 'UP',
          qdrant: 'UP',
          elasticsearch: 'UP',
        },
      };
    else if (path === '/api/v1/knowledge-bases/1') data = knowledgeBases[0];
    else if (path === '/api/v1/knowledge-bases/1/documents') {
      if (method === 'POST') {
        const form = await new Request(request.url(), {
          method: 'POST',
          headers: request.headers(),
          body: request.postDataBuffer()!,
        }).formData();
        const file = form.get('file') as File;
        const document: DocumentRecord = {
          id: 101 + documents.length,
          knowledgeBaseId: 1,
          originalName: file.name,
          contentType: file.type,
          extension: file.name.split('.').at(-1)!,
          sizeBytes: file.size,
          sha256: 'a'.repeat(64),
          status: 'PROCESSING',
          errorCode: null,
          errorMessage: null,
          createdAt: timestamp,
          updatedAt: timestamp,
        };
        files.set(document.id, Buffer.from(await file.arrayBuffer()));
        chunks.set(document.id, [
          {
            chunkId: (chunkId += 17),
            chunkIndex: 0,
            content:
              document.extension === 'txt'
                ? await file.text()
                : 'Mock PDF 证据：幂等键避免重复执行。',
            pageFrom: document.extension === 'pdf' ? 1 : null,
            pageTo: document.extension === 'pdf' ? 1 : null,
            sectionTitle: '测试证据',
          },
        ]);
        documents.push(document);
        data = document;
        status = 201;
        setTimeout(() => {
          document.status = 'READY';
          publish(
            { resource: 'DOCUMENT', knowledgeBaseId: 1, documentId: document.id },
            'DOCUMENT_READY',
            '文档已就绪',
            'READY',
          );
        }, 600);
      } else data = paginate(documents, url);
    } else if (/^\/api\/v1\/knowledge-bases\/1\/documents\/\d+/.test(path)) {
      const id = Number(path.split('/')[6]);
      const document = documents.find((item) => item.id === id);
      if (!document) {
        status = 404;
        code = 'DOCUMENT_NOT_FOUND';
        data = null;
      } else if (path.endsWith('/download')) {
        await route.fulfill({
          contentType: document.contentType,
          body: files.get(id)!,
          headers: { 'Content-Disposition': `attachment; filename="${document.originalName}"` },
        });
        return;
      } else if (path.endsWith('/context'))
        data = chunks.get(id); // Mock-only array convention.
      else if (path.endsWith('/chunks')) data = paginate(chunks.get(id) ?? [], url);
      else data = document;
    } else if (path === '/api/v1/knowledge-bases/1/conversations') {
      if (method === 'POST') {
        const input = request.postDataJSON();
        const conversation: Conversation = {
          id: 301 + conversations.length,
          knowledgeBaseId: 1,
          title: input.title || '测试会话',
          agentMode: input.agentMode,
          createdAt: timestamp,
          updatedAt: timestamp,
        };
        conversations.push(conversation);
        messages.set(conversation.id, []);
        data = conversation;
        status = 201;
      } else data = paginate(conversations, url);
    } else if (/^\/api\/v1\/conversations\/\d+\/messages$/.test(path)) {
      const id = Number(path.split('/')[4]);
      const history = messages.get(id)!;
      if (method === 'POST') {
        const input = request.postDataJSON();
        const user: ConversationMessage = {
          id: messageId++,
          conversationId: id,
          sequenceNo: history.length + 1,
          role: 'USER',
          content: input.content,
          citations: [],
          modelName: null,
          tokenUsage: null,
          createdAt: timestamp,
        };
        const assistant: ConversationMessage = {
          ...user,
          id: messageId++,
          sequenceNo: user.sequenceNo + 1,
          role: 'ASSISTANT',
          content: '**Mock 回答**：上传证据支持幂等恢复。[C1]',
          citations: [citation()],
        };
        history.push(user, assistant);
        data = { userMessage: user, assistantMessage: assistant };
      } else data = paginate(history, url);
    } else if (path === '/api/v1/agent/executions') {
      if (method === 'POST') {
        posts.execution += 1;
        const input = request.postDataJSON();
        expect(request.headers()['idempotency-key']).toMatch(/^[0-9a-f-]{36}$/);
        const execution = {
          ...mockPlanExecution(),
          conversationId: input.conversationId,
          agentMode: input.agentMode,
        };
        executions.set(execution.executionId, execution);
        data = execution;
        status = 202;
      } else {
        const list = [...executions.values()]
          .filter(
            (item) =>
              (!url.searchParams.get('status') || url.searchParams.get('status') === item.status) &&
              (!url.searchParams.get('agentMode') ||
                url.searchParams.get('agentMode') === item.agentMode),
          )
          .map((item) => ({
            executionId: item.executionId,
            knowledgeBaseId: item.knowledgeBaseId,
            conversationId: item.conversationId,
            agentMode: item.agentMode,
            status: item.status,
            currentStep: item.currentStep,
            startedAt: item.createdAt,
            updatedAt: item.updatedAt,
          }));
        data = paginate(list, url);
      }
    } else if (/^\/api\/v1\/agent\/executions\//.test(path)) {
      const id = path.split('/')[5];
      const execution = executions.get(id);
      expect(url.searchParams.get('knowledgeBaseId')).toBe('1');
      if (!execution) {
        status = 404;
        code = 'NOT_FOUND';
        data = null;
      } else if (path.endsWith('/resume') && method === 'POST') {
        posts.resume += 1;
        if (
          execution.status !== 'PAUSED' &&
          !(execution.status === 'FAILED' && execution.retryable)
        ) {
          status = 409;
          code = 'STATE_CONFLICT';
          data = null;
        } else {
          execution.status = 'RUNNING';
          execution.retryable = false;
          execution.errorCode = null;
          execution.version += 1;
          if (resumeConflict) {
            resumeConflict = false;
            status = 409;
            code = 'STATE_CONFLICT';
            data = null;
          } else {
            data = execution;
            status = 202;
          }
        }
      } else if (path.endsWith('/cancel') && method === 'POST') {
        posts.cancel += 1;
        execution.cancelRequested = true;
        execution.status = 'CANCELLED';
        data = execution;
      } else data = execution;
    } else throw new Error(`Unexpected workflow Mock request: ${method} ${path}`);
    if (code !== 'SUCCESS') message = 'Mock 执行状态冲突或资源不存在';
    await route.fulfill({ status, json: { code, message, data, traceId: 'workflow-rest-mock' } });
  });
  return {
    posts,
    executions,
    documents,
    knowledgeBases,
    subscriptions,
    fail(id: string) {
      const execution = executions.get(id)!;
      execution.status = 'FAILED';
      execution.retryable = true;
      execution.errorCode = 'MODEL_TIMEOUT';
      publish(
        { resource: 'EXECUTION', knowledgeBaseId: 1, executionId: id },
        'EXECUTION_FAILED',
        '执行已结束',
        'FAILED',
      );
    },
    progress(id: string) {
      publish(
        { resource: 'EXECUTION', knowledgeBaseId: 1, executionId: id },
        'STEP_STARTED',
        '步骤状态已更新',
        'RUNNING',
      );
    },
    complete(id: string) {
      const completed = completeMockPlan(executions.get(id)!);
      completed.result = {
        ...(completed.result as object),
        citations: [{ ...citation(), knowledgeBaseId: 1 }],
      };
      executions.set(id, completed);
      publish(
        { resource: 'EXECUTION', knowledgeBaseId: 1, executionId: id },
        'EXECUTION_COMPLETED',
        '执行已完成',
        'SUCCEEDED',
      );
    },
    concurrentResumeOnce() {
      resumeConflict = true;
    },
  };
}

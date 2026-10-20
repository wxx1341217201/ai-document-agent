import type {
  ConversationAgentMode,
  ConversationMessage,
  ExecutionResponse,
  ExecutionStatus,
} from './contracts';
import { conversationApi } from './conversation.api';
import { ApiClientError } from './client';
import { executionApi } from './execution.api';

export interface SendAgentMessageInput {
  knowledgeBaseId: number;
  conversationId: number;
  agentMode: ConversationAgentMode;
  content: string;
  /** Client turn identity; reused as the backend's Idempotency-Key for execution creation. */
  clientMessageId: string;
}

export type AgentSubmitResult =
  | { kind: 'completed'; message: ConversationMessage; userMessage: ConversationMessage }
  | {
      kind: 'execution';
      executionId: string;
      status: ExecutionStatus;
      execution: ExecutionResponse;
    };

export interface AgentGateway {
  sendMessage(input: SendAgentMessageInput): Promise<AgentSubmitResult>;
}

export const agentGateway: AgentGateway = {
  async sendMessage(input) {
    switch (input.agentMode) {
      case 'RAG':
      case 'RETRIEVAL_FIRST': {
        const response = await conversationApi.sendMessage(input.conversationId, {
          content: input.content,
        });
        return {
          kind: 'completed',
          message: response.assistantMessage,
          userMessage: response.userMessage,
        };
      }
      case 'REACT':
      case 'PLAN_EXECUTE_REFLECT': {
        const execution = await executionApi.create(
          {
            knowledgeBaseId: input.knowledgeBaseId,
            conversationId: input.conversationId,
            agentMode: input.agentMode,
            question: input.content,
          },
          input.clientMessageId,
        );
        return {
          kind: 'execution',
          executionId: execution.executionId,
          status: execution.status,
          execution,
        };
      }
      default:
        throw new ApiClientError({
          code: 'UNSUPPORTED_AGENT_MODE',
          message: '当前会话的 Agent 模式暂不支持，请重新选择会话。',
        });
    }
  },
};

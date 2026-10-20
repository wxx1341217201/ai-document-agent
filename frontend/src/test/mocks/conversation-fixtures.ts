import type {
  Conversation,
  ConversationMessage,
  CreateConversationRequest,
  RagCitation,
  SendConversationMessageResponse,
} from '../../api/contracts';

// FE06 in-memory Mock data, never used unless MSW is explicitly enabled.
const timestamp = '2026-10-11T08:30:00';
export const mockChatCitation: RagCitation = {
  citationId: 'C1',
  documentId: 101,
  documentName: 'catalyst-paper.pdf',
  chunkId: 1001,
  pageFrom: 5,
  pageTo: 5,
  quote: '消费者会先检查幂等键；已处理的消息不会再次执行业务逻辑。',
};

const initialConversations: Conversation[] = [
  {
    id: 301,
    knowledgeBaseId: 1,
    title: '重试机制分析',
    agentMode: 'RETRIEVAL_FIRST',
    createdAt: timestamp,
    updatedAt: timestamp,
  },
  {
    id: 302,
    knowledgeBaseId: 1,
    title: '旧版 RAG 会话',
    agentMode: 'RAG',
    createdAt: timestamp,
    updatedAt: timestamp,
  },
];

const initialMessages: Record<number, ConversationMessage[]> = {
  301: [
    {
      id: 3001,
      conversationId: 301,
      sequenceNo: 1,
      role: 'USER',
      content: '如何避免重复处理？',
      citations: [],
      modelName: null,
      tokenUsage: null,
      createdAt: timestamp,
    },
    {
      id: 3002,
      conversationId: 301,
      sequenceNo: 2,
      role: 'ASSISTANT',
      content: '**Mock 历史回答**：使用幂等键跳过已处理的消息。[C1]',
      citations: [mockChatCitation],
      modelName: 'mock-model',
      tokenUsage: null,
      createdAt: timestamp,
    },
  ],
  302: [],
};

let conversations: Conversation[];
let messages: Record<number, ConversationMessage[]>;
let nextConversationId: number;
let nextMessageId: number;

export function resetConversationFixtures() {
  conversations = structuredClone(initialConversations);
  messages = structuredClone(initialMessages);
  nextConversationId = 303;
  nextMessageId = 3003;
}

resetConversationFixtures();

export function getConversations(knowledgeBaseId: number) {
  return conversations
    .filter((item) => item.knowledgeBaseId === knowledgeBaseId)
    .sort((left, right) => right.updatedAt.localeCompare(left.updatedAt) || right.id - left.id);
}

export function getConversation(conversationId: number) {
  return conversations.find((item) => item.id === conversationId);
}

export function addConversation(knowledgeBaseId: number, input: CreateConversationRequest) {
  const conversation: Conversation = {
    id: nextConversationId++,
    knowledgeBaseId,
    title: input.title?.trim() || '新会话',
    agentMode: input.agentMode,
    createdAt: timestamp,
    updatedAt: timestamp,
  };
  conversations.push(conversation);
  messages[conversation.id] = [];
  return conversation;
}

export function getConversationMessages(conversationId: number) {
  return messages[conversationId] ?? [];
}

export function addConversationTurn(
  conversationId: number,
  content: string,
): SendConversationMessageResponse {
  const history = getConversationMessages(conversationId);
  const sequenceNo = (history.at(-1)?.sequenceNo ?? 0) + 1;
  const base = { conversationId, createdAt: timestamp, modelName: null, tokenUsage: null };
  const userMessage: ConversationMessage = {
    ...base,
    id: nextMessageId++,
    sequenceNo,
    role: 'USER',
    content,
    citations: [],
  };
  const citations =
    getConversation(conversationId)?.knowledgeBaseId === 1 ? [mockChatCitation] : [];
  const assistantMessage: ConversationMessage = {
    ...base,
    id: nextMessageId++,
    sequenceNo: sequenceNo + 1,
    role: 'ASSISTANT',
    content:
      '**Mock 回答**：已读取当前会话的历史消息。' +
      (citations.length ? '演示幂等处理的引用。[C1]' : '本知识库没有演示引用。'),
    citations,
    modelName: 'mock-model',
  };
  messages[conversationId] = [...history, userMessage, assistantMessage];
  const conversation = getConversation(conversationId);
  if (conversation) conversation.updatedAt = '2026-10-11T10:00:00';
  return { userMessage, assistantMessage };
}

export function removeConversation(conversationId: number) {
  conversations = conversations.filter((item) => item.id !== conversationId);
  delete messages[conversationId];
}

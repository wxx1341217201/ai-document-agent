import type { ConversationAgentMode } from '../../api/contracts';

export const agentModeLabels: Record<ConversationAgentMode, string> = {
  RAG: 'RAG',
  RETRIEVAL_FIRST: 'Retrieval-First',
  REACT: 'ReAct',
  PLAN_EXECUTE_REFLECT: 'Plan-Execute-Reflect',
};

export const newConversationModes = [
  { value: 'RETRIEVAL_FIRST', label: agentModeLabels.RETRIEVAL_FIRST },
  { value: 'REACT', label: agentModeLabels.REACT },
  { value: 'PLAN_EXECUTE_REFLECT', label: agentModeLabels.PLAN_EXECUTE_REFLECT },
];

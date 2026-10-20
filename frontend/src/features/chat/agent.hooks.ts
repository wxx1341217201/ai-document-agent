import { useMutation, useMutationState, useQueries, useQueryClient } from '@tanstack/react-query';
import { agentGateway, type AgentSubmitResult } from '../../api/agent-gateway';
import type {
  Conversation,
  ConversationMessage,
  SendConversationMessageRequest,
} from '../../api/contracts';
import { executionApi } from '../../api/execution.api';
import { queryKeys } from '../../api/query-keys';

interface AgentMessageVariables extends SendConversationMessageRequest {
  clientMessageId: string;
}

function useAgentSubmissions(conversationId?: number) {
  // Mutation state keeps the client message association across in-app navigation, including
  // the interval after POST succeeds and before the execution reaches a terminal state.
  const submissions = useMutationState({
    filters: {
      mutationKey: conversationId
        ? queryKeys.messages.send(conversationId)
        : queryKeys.messages.sends(),
      exact: Boolean(conversationId),
    },
    select: (mutation) => ({
      status: mutation.state.status,
      input: mutation.state.variables as AgentMessageVariables | undefined,
      result: mutation.state.data as AgentSubmitResult | undefined,
    }),
  });
  const accepted = submissions.flatMap(({ status, input, result }) =>
    status === 'success' && input && result?.kind === 'execution' ? [{ input, result }] : [],
  );
  const executions = useQueries({
    queries: accepted.map(({ result }) => ({
      queryKey: queryKeys.executions.detail(result.execution.knowledgeBaseId, result.executionId),
      queryFn: ({ signal }: { signal: AbortSignal }) =>
        executionApi.get(result.executionId, result.execution.knowledgeBaseId, signal),
      initialData: result.execution,
      // Cache observers retain message associations across navigation. The mounted
      // AgentExecutionMessage uses useExecutionProgress for realtime and REST refresh.
      enabled: false,
      gcTime: Infinity,
    })),
  });
  const executionMessages = accepted.map(({ input, result }, index) => {
    const query = executions[index];
    const execution = query.data ?? result.execution;
    return {
      assistantMessageId: 'assistant-' + input.clientMessageId,
      question: input.content,
      execution,
      isRefreshing: query.isFetching,
      refreshError: query.error,
      refresh: () => void query.refetch(),
    };
  });
  const pendingMessage = submissions.filter((item) => item.status === 'pending').at(-1)?.input;
  const lastSubmission = submissions.at(-1);
  const failedInput = lastSubmission?.status === 'error' ? lastSubmission.input : undefined;
  const isBusy =
    Boolean(pendingMessage) ||
    executionMessages.some(({ execution }) =>
      ['PENDING', 'RUNNING', 'PAUSED'].includes(execution.status),
    );
  return { pendingMessage, executionMessages, isBusy, failedInput };
}

export function useIsAgentBusy() {
  return useAgentSubmissions().isBusy;
}

export function useSendAgentMessage(conversation: Conversation) {
  const queryClient = useQueryClient();
  const activity = useAgentSubmissions(conversation.id);
  const mutation = useMutation({
    mutationKey: queryKeys.messages.send(conversation.id),
    retry: false,
    // Execution associations are session-only: the backend currently has no history linkage API.
    gcTime: Infinity,
    mutationFn: (input: AgentMessageVariables) =>
      agentGateway.sendMessage({
        ...input,
        knowledgeBaseId: conversation.knowledgeBaseId,
        conversationId: conversation.id,
        agentMode: conversation.agentMode,
      }),
    onMutate: () =>
      queryClient.cancelQueries({ queryKey: queryKeys.messages.list(conversation.id) }),
    onSuccess: (result) => {
      if (result.kind === 'execution') {
        queryClient.setQueryData(
          queryKeys.executions.detail(conversation.knowledgeBaseId, result.executionId),
          result.execution,
        );
        return;
      }
      queryClient.setQueryData<ConversationMessage[]>(
        queryKeys.messages.list(conversation.id),
        (previous = []) => {
          const messages = new Map(previous.map((message) => [message.id, message]));
          messages.set(result.userMessage.id, result.userMessage);
          messages.set(result.message.id, result.message);
          return [...messages.values()].sort(
            (left, right) => left.sequenceNo - right.sequenceNo || left.id - right.id,
          );
        },
      );
    },
    onSettled: () => {
      // Reconcile ambiguous failures with real persisted history; never auto-retry submission.
      void queryClient.invalidateQueries({ queryKey: queryKeys.messages.list(conversation.id) });
      void queryClient.invalidateQueries({
        queryKey: queryKeys.conversations.list(conversation.knowledgeBaseId),
      });
    },
  });

  return {
    ...activity,
    mutation,
    sendMessage: (input: SendConversationMessageRequest) =>
      mutation.mutateAsync({
        ...input,
        // A failed response can still mean that the server accepted an execution. Reuse the
        // key for an explicit retry of the same question; changed questions get a new key.
        clientMessageId:
          activity.failedInput?.content.trim() === input.content.trim()
            ? activity.failedInput.clientMessageId
            : crypto.randomUUID(),
      }),
    submissionNotice: mutation.isSuccess
      ? mutation.data.kind === 'completed'
        ? '消息已保存'
        : '任务已提交'
      : undefined,
  };
}

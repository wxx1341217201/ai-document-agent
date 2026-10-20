package com.wxx.aidocumentagent.conversation.api;

import java.time.LocalDateTime;
import java.util.List;

import com.wxx.aidocumentagent.common.api.BusinessException;
import com.wxx.aidocumentagent.common.web.GlobalExceptionHandler;
import com.wxx.aidocumentagent.common.web.TraceIdFilter;
import com.wxx.aidocumentagent.conversation.api.dto.ConversationMessageResponse;
import com.wxx.aidocumentagent.conversation.api.dto.ConversationResponse;
import com.wxx.aidocumentagent.conversation.api.dto.SendConversationMessageResponse;
import com.wxx.aidocumentagent.conversation.application.ConversationApplicationService;
import com.wxx.aidocumentagent.conversation.application.SendConversationMessageCommand;
import com.wxx.aidocumentagent.conversation.domain.ConversationAgentMode;
import com.wxx.aidocumentagent.conversation.domain.ConversationErrorCode;
import com.wxx.aidocumentagent.conversation.domain.ConversationMessageRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ConversationControllerTest {

    private ConversationApplicationService applicationService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        applicationService = mock(ConversationApplicationService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ConversationController(applicationService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilter(new TraceIdFilter())
                .build();
    }

    @Test
    void 在指定知识库创建会话并返回统一响应包络() throws Exception {
        when(applicationService.create(eq(7L), any())).thenReturn(conversationResponse());

        mockMvc.perform(post("/api/v1/knowledge-bases/7/conversations")
                        .header(TraceIdFilter.TRACE_ID_HEADER, "conversation-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\" 产品答疑 \",\"agentMode\":\"RAG\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string(TraceIdFilter.TRACE_ID_HEADER, "conversation-create"))
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.knowledgeBaseId").value(7))
                .andExpect(jsonPath("$.data.agentMode").value("RAG"));
    }

    @Test
    void 创建会话省略模式时选择检索优先() throws Exception {
        when(applicationService.create(eq(7L), any())).thenReturn(conversationResponse());

        mockMvc.perform(post("/api/v1/knowledge-bases/7/conversations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"产品答疑\"}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<com.wxx.aidocumentagent.conversation.application.CreateConversationCommand> command =
                ArgumentCaptor.forClass(com.wxx.aidocumentagent.conversation.application.CreateConversationCommand.class);
        verify(applicationService).create(eq(7L), command.capture());
        assertThat(command.getValue().agentMode()).isEqualTo(ConversationAgentMode.RETRIEVAL_FIRST);
    }

    @Test
    void 发消息仅做协议适配并返回用户与最终助手消息() throws Exception {
        when(applicationService.sendMessage(eq(42L), any())).thenReturn(sendResponse());

        mockMvc.perform(post("/api/v1/conversations/42/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\" 当前问题 \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userMessage.sequenceNo").value(1))
                .andExpect(jsonPath("$.data.assistantMessage.content").value("最终回答。[C1]"));

        ArgumentCaptor<SendConversationMessageCommand> command = ArgumentCaptor.forClass(
                SendConversationMessageCommand.class);
        verify(applicationService).sendMessage(eq(42L), command.capture());
        assertThat(command.getValue().content()).isEqualTo("当前问题");
    }

    @Test
    void 跨知识库或不存在的会话只返回稳定的会话未找到错误() throws Exception {
        when(applicationService.listMessages(42L, 0, 20)).thenThrow(
                new BusinessException(ConversationErrorCode.CONVERSATION_NOT_FOUND));

        mockMvc.perform(get("/api/v1/conversations/42/messages"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONVERSATION_NOT_FOUND"));
    }

    @Test
    void 空消息在进入应用层之前被校验拒绝() throws Exception {
        mockMvc.perform(post("/api/v1/conversations/42/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data[0].field").value("content"));
    }

    private ConversationResponse conversationResponse() {
        LocalDateTime timestamp = LocalDateTime.of(2026, 9, 23, 12, 0);
        return new ConversationResponse(42L, 7L, "产品答疑", ConversationAgentMode.RAG, timestamp, timestamp);
    }

    private SendConversationMessageResponse sendResponse() {
        LocalDateTime timestamp = LocalDateTime.of(2026, 9, 23, 12, 1);
        return new SendConversationMessageResponse(
                new ConversationMessageResponse(101L, 42L, 1L, ConversationMessageRole.USER, "当前问题", List.of(),
                        null, null, timestamp),
                new ConversationMessageResponse(102L, 42L, 2L, ConversationMessageRole.ASSISTANT, "最终回答。[C1]",
                        List.of(), "test-model", null, timestamp));
    }
}

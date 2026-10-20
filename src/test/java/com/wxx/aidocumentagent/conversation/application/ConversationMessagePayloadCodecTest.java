package com.wxx.aidocumentagent.conversation.application;

import java.util.List;

import com.wxx.aidocumentagent.conversation.infrastructure.persistence.ConversationMessage;
import com.wxx.aidocumentagent.rag.api.dto.RagCitationResponse;
import com.wxx.aidocumentagent.rag.application.RagChatCompletion;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationMessagePayloadCodecTest {

    @Test
    void 引用和token元数据以真实Json节点保存并可安全还原() {
        ConversationMessagePayloadCodec codec = new ConversationMessagePayloadCodec(new JsonMapper());
        RagCitationResponse citation = new RagCitationResponse("C1", 101L, "guide.txt", 1001L, 2, 2, "可核验证据");
        ConversationMessage message = ConversationMessage.assistant(42L, 2L, "最终回答。[C1]",
                codec.encodeCitations(List.of(citation)), "test-model",
                codec.encodeTokenUsage(new RagChatCompletion("最终回答。[C1]", "test-model", 11, 7, 18)));
        ReflectionTestUtils.setField(message, "id", 9L);

        assertThat(message.getCitationsJson().isArray()).isTrue();
        assertThat(message.getTokenUsageJson().isObject()).isTrue();
        assertThat(codec.toResponse(message).citations()).containsExactly(citation);
        assertThat(codec.toResponse(message).tokenUsage().totalTokens()).isEqualTo(18);
    }
}

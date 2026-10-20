package com.wxx.aidocumentagent.conversation.infrastructure.memory;

import java.time.Duration;
import java.util.List;

import com.wxx.aidocumentagent.conversation.ConversationMemoryProperties;
import com.wxx.aidocumentagent.conversation.domain.ConversationMessageRole;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisConversationMemoryStoreTest {

    @Test
    void 写入使用固定会话key和配置的TTL() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        ConversationMemoryProperties properties = new ConversationMemoryProperties();
        properties.setMemoryTtl(Duration.ofHours(24));
        RedisConversationMemoryStore store = new RedisConversationMemoryStore(redisTemplate, new JsonMapper(), properties);
        ConversationMemorySnapshot snapshot = new ConversationMemorySnapshot(2L, List.of(
                new ConversationMemoryEntry(1L, ConversationMessageRole.USER, "你好"),
                new ConversationMemoryEntry(2L, ConversationMessageRole.ASSISTANT, "你好，有什么可以帮你？")));

        store.put(42L, snapshot);

        org.mockito.ArgumentCaptor<String> payload = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(values).set(eq("chat:memory:42"), payload.capture(), eq(Duration.ofHours(24)));
        assertThat(payload.getValue()).contains("lastMessageSequence", "ASSISTANT");
    }

    @Test
    void 有效缓存值可被读取为带水位的快照() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        JsonMapper mapper = new JsonMapper();
        ConversationMemorySnapshot expected = new ConversationMemorySnapshot(1L,
                List.of(new ConversationMemoryEntry(1L, ConversationMessageRole.USER, "问题")));
        when(values.get("chat:memory:7")).thenReturn(mapper.writeValueAsString(expected));
        RedisConversationMemoryStore store = new RedisConversationMemoryStore(redisTemplate, mapper,
                new ConversationMemoryProperties());

        assertThat(store.get(7L)).contains(expected);
    }
}

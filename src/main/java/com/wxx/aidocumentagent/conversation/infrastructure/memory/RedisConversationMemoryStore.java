package com.wxx.aidocumentagent.conversation.infrastructure.memory;

import java.util.Optional;

import com.wxx.aidocumentagent.conversation.ConversationMemoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Redis 短期上下文实现。所有值带 TTL；缓存失败只会触发 MySQL 回源，不能让 Redis 成为会话可用性的单点。
 */
public final class RedisConversationMemoryStore implements ConversationMemoryStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisConversationMemoryStore.class);
    private static final String KEY_PREFIX = "chat:memory:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ConversationMemoryProperties properties;

    public RedisConversationMemoryStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                                        ConversationMemoryProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public Optional<ConversationMemorySnapshot> get(long conversationId) {
        String key = key(conversationId);
        try {
            String serialized = redisTemplate.opsForValue().get(key);
            if (serialized == null || serialized.isBlank()) {
                return Optional.empty();
            }
            return Optional.ofNullable(objectMapper.readValue(serialized, ConversationMemorySnapshot.class));
        }
        catch (JacksonException | IllegalArgumentException exception) {
            LOGGER.warn("会话短期上下文缓存内容无效，将从数据库重建: conversationId={}", conversationId);
            safelyDelete(key, conversationId);
            return Optional.empty();
        }
        catch (DataAccessException exception) {
            LOGGER.warn("读取会话短期上下文缓存失败，将从数据库重建: conversationId={}", conversationId);
            return Optional.empty();
        }
    }

    @Override
    public void put(long conversationId, ConversationMemorySnapshot snapshot) {
        String key = key(conversationId);
        try {
            String serialized = objectMapper.writeValueAsString(snapshot);
            redisTemplate.opsForValue().set(key, serialized, properties.getMemoryTtl());
        }
        catch (JacksonException exception) {
            LOGGER.warn("序列化会话短期上下文失败，将跳过缓存: conversationId={}", conversationId);
        }
        catch (DataAccessException exception) {
            LOGGER.warn("写入会话短期上下文缓存失败，将在下次请求从数据库重建: conversationId={}", conversationId);
        }
    }

    @Override
    public void evict(long conversationId) {
        String key = key(conversationId);
        try {
            redisTemplate.delete(key);
        }
        catch (DataAccessException exception) {
            LOGGER.warn("删除会话短期上下文缓存失败，缓存会在TTL后失效: conversationId={}", conversationId);
        }
    }

    private void safelyDelete(String key, long conversationId) {
        try {
            redisTemplate.delete(key);
        }
        catch (DataAccessException exception) {
            LOGGER.debug("删除无效会话缓存失败: conversationId={}", conversationId);
        }
    }

    private String key(long conversationId) {
        if (conversationId <= 0L) {
            throw new IllegalArgumentException("conversationId必须大于0");
        }
        return KEY_PREFIX + conversationId;
    }
}

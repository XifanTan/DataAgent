package org.example.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据缓存管理器
 * 按会话管理业务数据，支持数据别名机制
 */
@Slf4j
@Component
public class DataCache {

    // 会话级别缓存: sessionId -> (alias -> BusinessData)
    private final Map<String, Map<String, BusinessData>> sessionCaches = new ConcurrentHashMap<>();
    private static final int MAX_CACHE_SIZE_PER_SESSION = 100;

    /**
     * 存储业务数据
     */
    public String store(String sessionId, Object data) {
        String alias = "data_" + System.currentTimeMillis();
        store(sessionId, alias, data);
        return alias;
    }

    /**
     * 存储业务数据（指定别名）
     */
    public void store(String sessionId, String alias, Object data) {
        BusinessData businessData = BusinessData.create(alias, data);
        getOrCreateSessionCache(sessionId).put(alias, businessData);
        log.debug("Stored data alias={} for session={}", alias, sessionId);
    }

    /**
     * 获取业务数据
     */
    public BusinessData get(String sessionId, String alias) {
        Map<String, BusinessData> cache = sessionCaches.get(sessionId);
        if (cache == null) {
            throw new DataNotFoundException("Session not found: " + sessionId);
        }
        BusinessData data = cache.get(alias);
        if (data == null) {
            throw new DataNotFoundException("Data alias not found: " + alias);
        }
        return data;
    }

    /**
     * 仅获取简略结果（用于显示和轻量级传递）
     */
    public Object getBrief(String sessionId, String alias) {
        return get(sessionId, alias).getBrief();
    }

    /**
     * 获取完整数据（用于需要完整数据的操作如Python分析）
     */
    public Object getFull(String sessionId, String alias) {
        return get(sessionId, alias).getFullData();
    }

    /**
     * 检查数据是否存在
     */
    public boolean has(String sessionId, String alias) {
        Map<String, BusinessData> cache = sessionCaches.get(sessionId);
        return cache != null && cache.containsKey(alias);
    }

    /**
     * 清除会话的所有缓存数据
     */
    public void clear(String sessionId) {
        sessionCaches.remove(sessionId);
        log.debug("Cleared cache for session={}", sessionId);
    }

    /**
     * 获取或创建会话缓存
     */
    private Map<String, BusinessData> getOrCreateSessionCache(String sessionId) {
        return sessionCaches.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>());
    }

    /**
     * 获取缓存统计信息
     */
    public CacheStats getStats(String sessionId) {
        Map<String, BusinessData> cache = sessionCaches.get(sessionId);
        if (cache == null) {
            return new CacheStats(0, 0);
        }
        long totalSize = cache.values().stream()
                .mapToLong(BusinessData::getSize)
                .sum();
        return new CacheStats(cache.size(), totalSize);
    }

    /**
     * 数据未找到异常
     */
    public static class DataNotFoundException extends RuntimeException {
        public DataNotFoundException(String message) {
            super(message);
        }
    }

    /**
     * 缓存统计信息
     */
    public record CacheStats(int entryCount, long totalSizeBytes) {}
}
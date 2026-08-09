package com.smartview.infra.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartview.interview.model.CandidatePoolItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 候选问题池 Redis 存储仓库。
 *
 * 功能说明：
 * - 封装候选池 JSON 序列化与 TTL 管理；Redis 只做候选池暂存（缓存），
 *   权威状态在 MySQL，Redis 丢失可从快照/同步重生成重建（interview-policy.md 3.5）
 * - key 由调用方（FollowUpPoolService）按
 *   interview:candidate_pool:{sessionId}:{questionId}:{currentStage} 拼装
 * - TTL 固定 30 分钟（interview-policy.md 3.2）
 *
 * 容错说明：
 * - 序列化/反序列化失败只记日志返回 null，由调用方走重建链路，
 *   不影响主流程（候选池缺失可降级）
 * - Redis 连接异常（RedisConnectionFailureException 等 DataAccessException）
 *   同样按可降级路径处理（interview-policy.md 3.5）：读取视为缺失走重建，
 *   写入尽力而为跳过，与 JSON 序列化失败行为保持一致
 *
 * @author SmartView Team
 * @since 2026-08-07
 */
@Slf4j
@Component
public class CandidatePoolRedisRepository {

    /** 候选池过期时间：30 分钟（interview-policy.md 3.2） */
    public static final Duration POOL_TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public CandidatePoolRedisRepository(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 写入候选池并设置 TTL。
     *
     * @param key        候选池 key
     * @param candidates 候选题列表
     */
    public void save(String key, List<CandidatePoolItem> candidates) {
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(candidates), POOL_TTL);
        } catch (JsonProcessingException exception) {
            // 序列化失败不阻断调用方：候选池是可降级的缓存
            log.warn("候选池序列化失败，跳过写入，key={}, error={}", key, exception.getMessage());
        } catch (DataAccessException exception) {
            // Redis 连接异常按可降级路径处理（interview-policy.md 3.5）：缓存写入尽力而为，失败不阻断调用方
            log.warn("候选池写入 Redis 失败（连接异常），跳过写入，key={}, error={}", key, exception.getMessage());
        }
    }

    /**
     * 读取候选池；缺失、JSON 非法或 Redis 连接异常返回 null，由调用方走重建链路。
     */
    public List<CandidatePoolItem> read(String key) {
        String json;
        try {
            json = redisTemplate.opsForValue().get(key);
        } catch (DataAccessException exception) {
            // Redis 连接异常按可降级路径处理（interview-policy.md 3.5）：视为缺失走重建链路
            log.warn("候选池读取 Redis 失败（连接异常），视为缺失走重建，key={}, error={}", key, exception.getMessage());
            return null;
        }
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<CandidatePoolItem>>() {
            });
        } catch (JsonProcessingException exception) {
            log.warn("候选池 JSON 解析失败，视为缺失走重建，key={}, error={}", key, exception.getMessage());
            return null;
        }
    }

    /**
     * 把追问候选池并入同一 key：先移除既有 FOLLOW_UP 类型再追加，避免多次回答残留旧追问。
     *
     * 预生成池 key 缺失（异步预生成未完成/过期/Redis 丢失）时不创建"仅追问"池：
     * 否则会遮蔽缺失的同阶段换题与下一阶段入口候选，且该 key 一旦存在，
     * getPool 的 3.5 重建链（含按评估事实重生成追问）将不再触发。
     */
    public void mergeFollowUps(String key, List<CandidatePoolItem> followUps) {
        List<CandidatePoolItem> existing = read(key);
        if (existing == null) {
            log.warn("候选池 key 缺失，跳过追问合并，等待重建补齐，key={}", key);
            return;
        }
        List<CandidatePoolItem> merged = new ArrayList<>(existing);
        merged.removeIf(item -> "FOLLOW_UP".equals(item.getCandidateType()));
        merged.addAll(followUps);
        save(key, merged);
    }

    /**
     * 删除候选池 key。
     */
    public void delete(String key) {
        redisTemplate.delete(key);
    }
}

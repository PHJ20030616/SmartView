package com.smartview.infra.redis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 回答提交的在途互斥锁（按"会话 + 题目"去重）。
 *
 * 解决的问题：回答提交幂等只在"回答已落库"之后生效——重试请求查不到
 * interview_answer.request_id 记录时，会重新走一遍评估（一次 /evaluate 实测均值 11.5s），
 * 同一份回答因此重复消耗 LLM 配额。
 *
 * 为什么锁的粒度是"会话 + 题目"而不是 request_id：前端每次点击提交都会生成新的
 * request_id（幂等键），所以按 request_id 加锁挡不住"超时后重新点击"这一类最典型的重复提交；
 * 而一道题在业务上只能被回答一次（interview_answer 对题目有唯一索引），
 * 因此"会话 + 题目"才是真正互斥的单元。
 *
 * 容错取舍：Redis 不可用时 {@link #tryAcquire} 返回 true（放行）。理由是锁只用于
 * 减少重复计算，属于优化而非正确性保证——正确性（不会重复落库）仍由 request_id 与题目的
 * 唯一索引兜底；反过来，若 Redis 故障就拒绝提交，会把缓存故障升级成"面试无法作答"。
 * 需要明确的是：**放行期间"重复评估消耗 LLM 配额"这件事是挡不住的**，唯一索引只能在评估
 * 之后拦下落库，这一取舍已记录在 interview-policy.md 5.2。因此故障时打 warn 日志
 * （日志里带 session/question，便于事后用 llm_call_log 按 trace 统计重复评估）。
 *
 * @author SmartView Team
 * @since 2026-09-14
 */
@Slf4j
@Component
public class SubmitInFlightLock {

    /** key 模板：interview:submit_inflight:{sessionId}:{questionId} */
    private static final String KEY_TEMPLATE = "interview:submit_inflight:%d:%d";

    /**
     * 锁存活时间。
     *
     * 取值依据：必须覆盖"一次提交在服务端可能占用的最长时间"，否则首个请求仍在评估时锁已过期，
     * 重复请求照样能进来，锁定就失去意义。最坏路径 ≈ AI 读超时 60s（application.yml
     * ai-service.read-timeout-ms；`/evaluate` 内部还有多次串行 LLM 调用）
     * + 连接池排队/建连 + 落库事务，因此按读超时的 2 倍取 120s 留出余量。
     *
     * 不能无上限：进程崩溃等场景下锁只能靠 TTL 自动消失，一次抖动最多影响该题 120 秒，
     * 期间用户会收到"正在评估中"而不是重复消耗配额——这是刻意选择的失败方向。
     */
    private static final Duration LOCK_TTL = Duration.ofSeconds(120);

    private final StringRedisTemplate redisTemplate;

    public SubmitInFlightLock(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 尝试获取某道题的在途提交锁。
     *
     * @param sessionId  会话 ID
     * @param questionId 当前题目 ID
     * @return true 表示本次请求可以继续；false 表示该题正在评估中
     */
    public boolean tryAcquire(Long sessionId, Long questionId) {
        String key = lockKey(sessionId, questionId);
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_TTL);
            return Boolean.TRUE.equals(acquired);
        } catch (DataAccessException exception) {
            log.warn("提交互斥锁获取失败（Redis 异常），放行本次提交 key={} error={}",
                    key, exception.getMessage());
            return true;
        }
    }

    /**
     * 释放某道题的在途提交锁（无论提交成功或失败都调用，失败时用户可立即重试）。
     */
    public void release(Long sessionId, Long questionId) {
        String key = lockKey(sessionId, questionId);
        try {
            redisTemplate.delete(key);
        } catch (DataAccessException exception) {
            // 释放失败不抛出：锁会在 TTL 到期后自动消失，不影响本轮提交结果
            log.warn("提交互斥锁释放失败（Redis 异常），等待 TTL 自动过期 key={} error={}",
                    key, exception.getMessage());
        }
    }

    private String lockKey(Long sessionId, Long questionId) {
        return String.format(KEY_TEMPLATE, sessionId, questionId);
    }
}

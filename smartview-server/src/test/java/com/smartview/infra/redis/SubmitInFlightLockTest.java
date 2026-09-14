package com.smartview.infra.redis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 提交在途互斥锁测试。
 *
 * 覆盖验收标准：
 * 1. 首次获取成功并写入带有 TTL 的 key（TTL 必须远大于 AI 读超时 60s，
 *    否则首个请求仍在评估（含连接排队与落库）时锁已过期，重复提交照样进来）；
 * 2. 同一道题已持有时返回 false（重复提交被拒）；
 * 3. Redis 异常时放行且释放不抛异常——锁是优化手段，缓存故障不能升级成"无法提交回答"。
 *
 * 锁粒度为"会话 + 题目"：前端每次点击都会生成新的 request_id，
 * 只有按题目加锁才能挡住"超时后重新点击"这一类重复提交。
 */
@ExtendWith(MockitoExtension.class)
class SubmitInFlightLockTest {

    private static final String KEY = "interview:submit_inflight:1:11";

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private SubmitInFlightLock lock;

    @BeforeEach
    void setUp() {
        lock = new SubmitInFlightLock(redisTemplate);
    }

    @Test
    void tryAcquire_未持有时获取成功且TTL覆盖读超时() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);

        assertThat(lock.tryAcquire(1L, 11L)).isTrue();

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).setIfAbsent(eq(KEY), eq("1"), ttl.capture());
        // 必须覆盖 AI 读超时 60s 加余量（连接排队 + 落库），这里按读超时 2 倍取 120s：
        // 一旦锁早于首个请求结束就过期，重复提交会重新跑一遍评估、白烧 LLM 配额
        assertThat(ttl.getValue()).isGreaterThanOrEqualTo(Duration.ofSeconds(120));
    }

    @Test
    void tryAcquire_同一道题已被持有时返回false() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);

        assertThat(lock.tryAcquire(1L, 11L)).isFalse();
    }

    @Test
    void tryAcquire_Redis异常时放行而不是阻断提交() {
        when(redisTemplate.opsForValue())
                .thenThrow(new DataAccessResourceFailureException("redis down"));

        assertThat(lock.tryAcquire(1L, 11L)).isTrue();
    }

    @Test
    void release_删除对应key() {
        lock.release(1L, 11L);

        verify(redisTemplate).delete(KEY);
    }

    @Test
    void release_Redis异常不抛出() {
        when(redisTemplate.delete(anyString()))
                .thenThrow(new DataAccessResourceFailureException("redis down"));

        assertThatCode(() -> lock.release(1L, 11L)).doesNotThrowAnyException();
    }
}

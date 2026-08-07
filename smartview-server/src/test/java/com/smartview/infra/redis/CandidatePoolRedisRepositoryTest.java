package com.smartview.infra.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartview.interview.model.CandidatePoolItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 候选池 Redis 仓库测试。覆盖：写入带 TTL、读取命中/缺失、追问池并入、删除。 */
@ExtendWith(MockitoExtension.class)
class CandidatePoolRedisRepositoryTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;

    private CandidatePoolRedisRepository repository;
    private static final String KEY = "interview:candidate_pool:1:11:BASIC";

    @BeforeEach
    void setUp() {
        // 该桩仅被 save/read/mergeFollowUps 使用，delete 测试不触发 opsForValue；
        // 用 lenient 避免 Mockito 严格桩对共享 setUp 桩的 UnnecessaryStubbing 报错（与项目现有测试约定一致）
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        repository = new CandidatePoolRedisRepository(redisTemplate, new ObjectMapper());
    }

    private CandidatePoolItem item(String type, String topic) {
        return CandidatePoolItem.builder()
                .questionText("关于" + topic + "的问题。")
                .topic(topic)
                .stage("BASIC")
                .candidateType(type)
                .sourceType("KNOWLEDGE_BASE")
                .build();
    }

    @Test
    void save_写入Redis并设置30分钟TTL() throws Exception {
        repository.save(KEY, List.of(item("SAME_STAGE_SWITCH", "Java 并发")));

        verify(valueOps).set(eq(KEY), any(String.class), eq(Duration.ofMinutes(30)));
    }

    @Test
    void read_命中时反序列化返回() throws Exception {
        when(valueOps.get(KEY)).thenReturn(
                "[{\"questionText\":\"问题\",\"topic\":\"Java 并发\",\"stage\":\"BASIC\","
                + "\"candidateType\":\"SAME_STAGE_SWITCH\",\"sourceType\":\"KNOWLEDGE_BASE\"}]");

        List<CandidatePoolItem> pool = repository.read(KEY);

        assertThat(pool).hasSize(1);
        assertThat(pool.get(0).getTopic()).isEqualTo("Java 并发");
        assertThat(pool.get(0).getCandidateType()).isEqualTo("SAME_STAGE_SWITCH");
    }

    @Test
    void read_缺失返回null() {
        when(valueOps.get(KEY)).thenReturn(null);

        assertThat(repository.read(KEY)).isNull();
    }

    @Test
    void read_非法JSON返回null() {
        when(valueOps.get(KEY)).thenReturn("not-json{");

        assertThat(repository.read(KEY)).isNull();
    }

    @Test
    void mergeFollowUps_替换既有FOLLOW_UP并保留其他类型() throws Exception {
        when(valueOps.get(KEY)).thenReturn(
                "[{\"questionText\":\"旧追问\",\"topic\":\"t\",\"stage\":\"BASIC\","
                + "\"candidateType\":\"FOLLOW_UP\"},"
                + "{\"questionText\":\"换题\",\"topic\":\"t2\",\"stage\":\"BASIC\","
                + "\"candidateType\":\"SAME_STAGE_SWITCH\"}]");

        repository.mergeFollowUps(KEY, List.of(item("FOLLOW_UP", "新追问")));

        // 写入的 JSON 应只含 1 道新 FOLLOW_UP + 原 SAME_STAGE_SWITCH
        verify(valueOps).set(eq(KEY), any(String.class), eq(Duration.ofMinutes(30)));
        String capturedJson = captureSetValue();
        assertThat(capturedJson).contains("\"新追问\"").contains("SAME_STAGE_SWITCH");
        assertThat(capturedJson).doesNotContain("旧追问");
    }

    @Test
    void delete_删除key() {
        repository.delete(KEY);
        verify(redisTemplate).delete(KEY);
    }

    private String captureSetValue() throws Exception {
        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq(KEY), captor.capture(), eq(Duration.ofMinutes(30)));
        return captor.getValue();
    }
}

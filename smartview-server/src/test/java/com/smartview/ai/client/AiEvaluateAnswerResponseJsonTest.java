package com.smartview.ai.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartview.interview.model.CandidatePoolItem;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ai-api 响应 → Spring 手写 DTO 的反序列化守卫。
 *
 * 背景：`AiEvaluateAnswerResponse` 与 `CandidatePoolItem` 是 Spring 侧手写模型
 * （ai-api 契约未接入 openapi-generator，见 contracts/README），字段名与契约不一致时
 * **不会报错**，只会静默变成 null。追问类型 `followUpKind`（v1.3 新增）正是这种字段：
 * 反序列化失败的表现是"追问类型永远缺失"，决策侧只会走软回退，看板上无法察觉。
 *
 * 这里刻意使用生产同款 ObjectMapper（`MappingJackson2HttpMessageConverter` 内部的
 * Jackson2ObjectMapperBuilder 结果，关闭 FAIL_ON_UNKNOWN_PROPERTIES），而不是
 * `new ObjectMapper()`，因为两者的未知字段策略不同——用错会让本测试与线上行为脱节。
 */
class AiEvaluateAnswerResponseJsonTest {

    /** 与生产 RestTemplate 默认转换器一致的 ObjectMapper（未知字段忽略）。 */
    private final ObjectMapper objectMapper = new MappingJackson2HttpMessageConverter().getObjectMapper();

    @Test
    void 追问候选的followUpKind与其余字段可完整反序列化() throws Exception {
        // 样例取自真实 /interview/evaluate 响应（GAP/DEEP 两型）
        String json = """
                {
                  "success": true,
                  "score": 84,
                  "level": "GOOD",
                  "matchedPoints": ["说明了可见性"],
                  "missingPoints": [],
                  "riskPoints": [],
                  "followUpCandidates": [
                    {
                      "questionText": "你提到 FC 模式会带来 Schema 膨胀，具体怎么控制？",
                      "topic": "Function Calling 与 ReAct 范式对比",
                      "stage": "PROJECT",
                      "candidateType": "FOLLOW_UP",
                      "followUpKind": "GAP",
                      "sourceType": "KNOWLEDGE_BASE",
                      "expectedPoints": ["参数裁剪", "Schema 复用"],
                      "targetPoint": "工具规模治理",
                      "reason": "回答缺失要点补充追问"
                    },
                    {
                      "questionText": "你说的按需注入工具具体如何落地？",
                      "topic": "Function Calling 与 ReAct 范式对比",
                      "stage": "PROJECT",
                      "candidateType": "FOLLOW_UP",
                      "followUpKind": "DEEP",
                      "sourceType": "EXPERIENCE_CASE",
                      "expectedPoints": ["动态筛选"],
                      "targetPoint": "工程落地细节",
                      "reason": "结合回答亮点深入追问"
                    }
                  ]
                }
                """;

        AiEvaluateAnswerResponse response = objectMapper.readValue(json, AiEvaluateAnswerResponse.class);

        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getScore()).isEqualTo(84);
        assertThat(response.getFollowUpCandidates()).hasSize(2);
        assertThat(response.getFollowUpCandidates())
                .extracting(CandidatePoolItem::getFollowUpKind)
                .containsExactly(CandidatePoolItem.KIND_GAP, CandidatePoolItem.KIND_DEEP);
        assertThat(response.getFollowUpCandidates().get(0).getCandidateType())
                .isEqualTo(CandidatePoolItem.TYPE_FOLLOW_UP);
        assertThat(response.getFollowUpCandidates().get(0).getExpectedPoints())
                .containsExactly("参数裁剪", "Schema 复用");
    }

    @Test
    void 缺少followUpKind的旧数据仍可反序列化() throws Exception {
        // 兼容：Redis 里的历史候选、以及未升级的 AI 服务响应都没有该字段
        String json = """
                {"success": true, "score": 60, "level": "FAIR",
                 "followUpCandidates": [{"questionText": "追问", "topic": "Java 并发",
                 "stage": "BASIC", "candidateType": "FOLLOW_UP"}]}
                """;

        AiEvaluateAnswerResponse response = objectMapper.readValue(json, AiEvaluateAnswerResponse.class);

        assertThat(response.getFollowUpCandidates()).hasSize(1);
        assertThat(response.getFollowUpCandidates().get(0).getFollowUpKind()).isNull();
    }
}

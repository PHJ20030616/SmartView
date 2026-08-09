package com.smartview.interview;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 面试四表迁移集成测试（V7）。
 *
 * 使用纯 H2（MySQL 兼容模式）验证 V7 迁移脚本与 Task 5.1 验收标准，
 * 不启动 Spring 上下文、不依赖外部基础设施。
 *
 * 说明：由于既有的 V2 迁移在三张表上创建了同名索引（idx_deleted 等），而 H2
 * 的索引名是 schema 级全局唯一（MySQL 则按表隔离），导致完整迁移链无法在 H2
 * 上应用（V2 即失败）。因此本测试仅应用 V7，并内联创建 V7 外键依赖的三张
 * 父表桩（user / resume_profile / profile_analysis），聚焦验证本任务交付的
 * 表结构与约束。
 *
 * 覆盖验收标准：
 * 1. 会话、问题、回答、评估四类数据可关联查询（JOIN 四表 + 画像链路）；
 * 2. current_question_id 能指向当前问题；
 * 3. graph_thread_id、latest_checkpoint_id、stage_plan_json、stage_coverage_json、
 *    version、request_id 等字段存在且可读写；
 * 4. interview_answer 对同一 question_id 只能有一份有效回答（唯一索引兜底）。
 *
 * H2 连接参数与 application-test.yml 保持一致（MODE=MySQL、大小写不敏感等）。
 */
class InterviewTablesMigrationTest {

    /**
     * 计数器：为每个用例分配独立的 H2 内存库名，避免用例间数据相互污染。
     */
    private static final AtomicInteger DB_SEQ = new AtomicInteger(1);

    /**
     * 用于对 JSON 字段做语义级比较，规避 H2 与 MySQL 对 JSON 列的序列化差异。
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    private JdbcTemplate jdbc;

    @BeforeEach
    void applyMigrationsOnFreshH2() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:sv_interview_" + DB_SEQ.getAndIncrement()
                + ";MODE=MySQL;NON_KEYWORDS=USER;DATABASE_TO_LOWER=TRUE;"
                + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        jdbc = new JdbcTemplate(dataSource);

        // 内联创建 V7 外键依赖的父表桩（仅含 V7 外键所需的主键列与测试插入列）。
        // 设计取舍：不套用 V1/V2/V6 的真实结构，避免受既有 V2 在 H2 上的兼容问题影响。
        jdbc.execute("CREATE TABLE `user` ("
                + "`id` BIGINT NOT NULL AUTO_INCREMENT, "
                + "`username` VARCHAR(50) NOT NULL, "
                + "`password_hash` VARCHAR(255) NOT NULL, "
                + "`nickname` VARCHAR(100) NULL, "
                + "`deleted` TINYINT(1) NOT NULL DEFAULT 0, "
                + "PRIMARY KEY (`id`))");
        jdbc.execute("CREATE TABLE `resume_profile` ("
                + "`id` BIGINT NOT NULL AUTO_INCREMENT, "
                + "`user_id` BIGINT NOT NULL, "
                + "`deleted` TINYINT(1) NOT NULL DEFAULT 0, "
                + "PRIMARY KEY (`id`))");
        jdbc.execute("CREATE TABLE `profile_analysis` ("
                + "`id` BIGINT NOT NULL AUTO_INCREMENT, "
                + "`user_id` BIGINT NOT NULL, "
                + "`resume_profile_id` BIGINT NOT NULL, "
                + "`role_direction` VARCHAR(50) NOT NULL, "
                + "`profile_version` INT NOT NULL, "
                + "`deleted` TINYINT(1) NOT NULL DEFAULT 0, "
                + "PRIMARY KEY (`id`))");

        // 应用 V7 + V8 迁移脚本（ResourceDatabasePopulator 负责按分号切分语句、剥离 -- 注释）
        ResourceDatabasePopulator populator =
                new ResourceDatabasePopulator(
                        new ClassPathResource("db/migration/V7__create_interview_tables.sql"),
                        new ClassPathResource("db/migration/V8__add_interview_answer_request_id_unique.sql"));
        populator.execute(dataSource);
    }

    @Test
    void fourTablesCanBeJoinedAndCoreFieldsRoundTrip() throws Exception {
        // 构造完整依赖链：user → resume_profile → profile_analysis → 会话 → 问题 → 回答 → 评估
        long userId = insertUser("interview_tables_user");
        long resumeProfileId = insertResumeProfile(userId);
        long profileAnalysisId = insertProfileAnalysis(userId, resumeProfileId);

        // 会话：写入核心恢复字段（graph_thread_id、checkpoint、阶段计划/覆盖、乐观锁版本）
        long sessionId = insertSession(userId, resumeProfileId, profileAnalysisId);

        // 问题：首题为 OPENING（无父题），第二题为追问（parent 指向首题）
        long firstQuestionId = insertQuestion(sessionId, userId, 1, null, "OPENING", "线程池原理");
        long secondQuestionId = insertQuestion(sessionId, userId, 2, firstQuestionId, "FOLLOW_UP", "线程池拒绝策略");

        // 更新会话的当前问题指针，验证 current_question_id 指向当前问题
        jdbc.update("UPDATE interview_session SET current_question_id = ? WHERE id = ?",
                secondQuestionId, sessionId);

        // 回答：携带幂等 request_id，作答方式/耗时/提交时间
        long answerId = insertAnswer(sessionId, secondQuestionId, userId);

        // 评估：得分/等级/命中与缺失要点/下一步决策/候选池快照/选中下一题
        long evaluationId = insertEvaluation(sessionId, secondQuestionId, answerId);

        // 关联查询：一次 JOIN 贯穿四表，验证四类数据可关联查询。
        // 显式限定 q.id = 当前问题，避免依赖"仅当前问题有回答"的隐式单行前提。
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT s.id AS session_id, s.status, s.current_question_id, "
                        + "s.graph_thread_id, s.latest_checkpoint_id, "
                        + "s.stage_plan_json, s.stage_coverage_json, s.version, "
                        + "s.expected_min_questions, s.expected_max_questions, s.end_reason, "
                        + "q.id AS question_id, q.question_order, q.parent_question_id, "
                        + "q.question_text, q.stage, q.question_type, q.status AS q_status, "
                        + "a.id AS answer_id, a.answer_text, a.request_id, a.answer_mode, "
                        + "e.id AS evaluation_id, e.score, e.level, e.next_action, "
                        + "e.selected_next_question_id, e.model_name "
                        + "FROM interview_session s "
                        + "JOIN interview_question q ON q.session_id = s.id "
                        + "JOIN interview_answer a ON a.question_id = q.id "
                        + "JOIN answer_evaluation e ON e.answer_id = a.id "
                        + "WHERE s.id = ? AND q.id = ?",
                sessionId, secondQuestionId);

        // —— 断言会话核心字段 ——
        assertThat(row.get("status")).isEqualTo("IN_PROGRESS");
        assertThat(row.get("graph_thread_id")).isEqualTo("thread-test-0001");
        assertThat(row.get("latest_checkpoint_id")).isEqualTo("checkpoint-test-0001");

        // 阶段计划/覆盖为 JSON 字段，做语义级比较（解析后对比），
        // 规避 H2 与 MySQL 对 JSON 列序列化形式的差异。
        JsonNode stagePlan = parseJson(row.get("stage_plan_json"));
        assertThat(stagePlan.path("stages")).hasSize(3);
        assertThat(stagePlan.path("stages").get(0).asText()).isEqualTo("BASIC");
        assertThat(stagePlan.path("totalMin").asInt()).isEqualTo(8);

        JsonNode stageCoverage = parseJson(row.get("stage_coverage_json"));
        assertThat(stageCoverage.path("BASIC").path("questionCount").asInt()).isEqualTo(2);
        assertThat(stageCoverage.path("BASIC").path("coveredTopics").get(0).asText())
                .isEqualTo("线程池");

        assertThat(((Number) row.get("version")).intValue()).isEqualTo(3);
        assertThat(((Number) row.get("expected_min_questions")).intValue()).isEqualTo(8);
        assertThat(((Number) row.get("expected_max_questions")).intValue()).isEqualTo(20);

        // —— 断言 current_question_id 指向当前问题（第二题） ——
        assertThat(((Number) row.get("current_question_id")).longValue()).isEqualTo(secondQuestionId);
        assertThat(((Number) row.get("question_id")).longValue()).isEqualTo(secondQuestionId);

        // —— 断言问题字段 ——
        // 正文由 insertQuestion 按 "请说明{topic}有哪些要点？" 生成，需保持一致
        assertThat(row.get("question_text")).isEqualTo("请说明线程池拒绝策略有哪些要点？");
        assertThat(row.get("stage")).isEqualTo("BASIC");
        assertThat(row.get("question_type")).isEqualTo("FOLLOW_UP");
        assertThat(((Number) row.get("parent_question_id")).longValue()).isEqualTo(firstQuestionId);

        // —— 断言回答字段（含幂等 request_id） ——
        assertThat(row.get("answer_text")).isEqualTo("AbortPolicy、CallerRunsPolicy、DiscardPolicy、DiscardOldestPolicy");
        assertThat(row.get("request_id")).isEqualTo("req-test-0001");
        assertThat(row.get("answer_mode")).isEqualTo("TEXT");

        // —— 断言评估字段 ——
        assertThat(((Number) row.get("score")).intValue()).isEqualTo(85);
        assertThat(row.get("level")).isEqualTo("GOOD");
        assertThat(row.get("next_action")).isEqualTo("FOLLOW_UP");
        assertThat(((Number) row.get("selected_next_question_id")).longValue())
                .isEqualTo(secondQuestionId);
        assertThat(row.get("model_name")).isEqualTo("qwen-plus");

        // —— 断言外键关联可反向追溯（评估 → 回答 → 问题 → 会话 → 画像分析） ——
        assertThat(((Number) row.get("evaluation_id")).longValue()).isEqualTo(evaluationId);
        assertThat(((Number) row.get("answer_id")).longValue()).isEqualTo(answerId);

        Integer profileAnalysisRef = jdbc.queryForObject(
                "SELECT profile_analysis_id FROM interview_session WHERE id = ?", Integer.class, sessionId);
        assertThat(profileAnalysisRef).isEqualTo((int) profileAnalysisId);

        // —— 断言问题列表可按会话查询并按序号排序 ——
        List<Long> questionIds = jdbc.queryForList(
                "SELECT id FROM interview_question WHERE session_id = ? ORDER BY question_order",
                Long.class, sessionId);
        assertThat(questionIds).containsExactly(firstQuestionId, secondQuestionId);
    }

    @Test
    void onlyOneValidAnswerAllowedPerQuestion() throws Exception {
        // 构造最小依赖链
        long userId = insertUser("interview_tables_unique_user");
        long resumeProfileId = insertResumeProfile(userId);
        long profileAnalysisId = insertProfileAnalysis(userId, resumeProfileId);
        long sessionId = insertSession(userId, resumeProfileId, profileAnalysisId);
        long questionId = insertQuestion(sessionId, userId, 1, null, "OPENING", "JVM内存模型");
        insertAnswer(sessionId, questionId, userId);

        // 同一问题再次插入有效回答（deleted=0）必须被唯一索引拦截
        assertThatThrownBy(() -> insertAnswer(sessionId, questionId, userId))
                .isInstanceOf(DataIntegrityViolationException.class);

        // 同一问题只能查到一份有效回答
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM interview_answer WHERE question_id = ? AND deleted = 0",
                Integer.class, questionId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void requestIdIsGloballyUnique() {
        // 构造最小依赖链
        long userId = insertUser("interview_tables_req_user");
        long resumeProfileId = insertResumeProfile(userId);
        long profileAnalysisId = insertProfileAnalysis(userId, resumeProfileId);
        long sessionId = insertSession(userId, resumeProfileId, profileAnalysisId);
        long q1 = insertQuestion(sessionId, userId, 1, null, "OPENING", "JVM内存模型");
        long q2 = insertQuestion(sessionId, userId, 2, null, "OPENING", "并发工具");
        insertAnswer(sessionId, q1, userId, "req-unique-0001");

        // 不同问题复用同一 request_id 必须被全局唯一索引拦截（回答提交幂等依赖）
        assertThatThrownBy(() -> insertAnswer(sessionId, q2, userId, "req-unique-0001"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void circularForeignKeyUsesSetNullOnDelete() {
        // 构造最小依赖链
        long userId = insertUser("interview_tables_fk_user");
        long resumeProfileId = insertResumeProfile(userId);
        long profileAnalysisId = insertProfileAnalysis(userId, resumeProfileId);
        long sessionId = insertSession(userId, resumeProfileId, profileAnalysisId);

        long parentId = insertQuestion(sessionId, userId, 1, null, "OPENING", "Java集合");
        long followUpId = insertQuestion(sessionId, userId, 2, parentId, "FOLLOW_UP", "HashMap扩容");
        jdbc.update("UPDATE interview_session SET current_question_id = ? WHERE id = ?",
                followUpId, sessionId);

        // 硬删除父问题：追问的 parent_question_id 应被外键置空（ON DELETE SET NULL）
        jdbc.update("DELETE FROM interview_question WHERE id = ?", parentId);
        Long parentAfter = jdbc.queryForObject(
                "SELECT parent_question_id FROM interview_question WHERE id = ?", Long.class, followUpId);
        assertThat(parentAfter).isNull();

        // 硬删除当前问题：会话的 current_question_id 应被外键置空
        // （验证循环引用外键同样生效，业务上当前问题走软删除不会触发）
        jdbc.update("DELETE FROM interview_question WHERE id = ?", followUpId);
        Long currentAfter = jdbc.queryForObject(
                "SELECT current_question_id FROM interview_session WHERE id = ?", Long.class, sessionId);
        assertThat(currentAfter).isNull();
    }

    // ==================== 测试数据构造辅助方法 ====================

    private long insertUser(String username) {
        return insertAndReturnKey(
                "INSERT INTO `user` (username, password_hash, nickname) VALUES (?, ?, ?)",
                username, "hashed-password", username);
    }

    private long insertResumeProfile(long userId) {
        return insertAndReturnKey(
                "INSERT INTO resume_profile (user_id) VALUES (?)",
                userId);
    }

    private long insertProfileAnalysis(long userId, long resumeProfileId) {
        return insertAndReturnKey(
                "INSERT INTO profile_analysis (user_id, resume_profile_id, role_direction, profile_version) "
                        + "VALUES (?, ?, ?, ?)",
                userId, resumeProfileId, "JAVA_BACKEND", 1);
    }

    private long insertSession(long userId, long resumeProfileId, long profileAnalysisId) {
        return insertAndReturnKey(
                "INSERT INTO interview_session "
                        + "(user_id, resume_profile_id, profile_analysis_id, role_direction, status, "
                        + "question_count, expected_min_questions, expected_max_questions, "
                        + "stage_plan_json, stage_coverage_json, graph_thread_id, latest_checkpoint_id, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                userId, resumeProfileId, profileAnalysisId, "JAVA_BACKEND", "IN_PROGRESS",
                2, 8, 20,
                "{\"stages\":[\"BASIC\",\"PROJECT\",\"SCENARIO\"],\"totalMin\":8}",
                "{\"BASIC\":{\"questionCount\":2,\"coveredTopics\":[\"线程池\"]}}",
                "thread-test-0001", "checkpoint-test-0001", 3);
    }

    private long insertQuestion(long sessionId, long userId, int order, Long parentQuestionId,
                                String questionType, String topic) {
        return insertAndReturnKey(
                "INSERT INTO interview_question "
                        + "(session_id, user_id, question_order, parent_question_id, stage, question_type, "
                        + "topic, question_text, source_type, knowledge_refs_json, case_refs_json, "
                        + "expected_points_json, status, asked_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                sessionId, userId, order, parentQuestionId, "BASIC", questionType,
                topic, "请说明" + topic + "有哪些要点？", "KNOWLEDGE_BASE",
                "[{\"knowledgeId\":\"kb-1\",\"snippet\":\"线程池拒绝策略\"}]",
                "[{\"caseId\":\"case-1\"}]",
                "[\"掌握四种拒绝策略\",\"能说明适用场景\"]",
                "ASKED");
    }

    private long insertAnswer(long sessionId, long questionId, long userId) {
        return insertAnswer(sessionId, questionId, userId, "req-test-0001");
    }

    private long insertAnswer(long sessionId, long questionId, long userId, String requestId) {
        return insertAndReturnKey(
                "INSERT INTO interview_answer "
                        + "(session_id, question_id, user_id, answer_text, answer_mode, "
                        + "duration_seconds, request_id, submitted_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                sessionId, questionId, userId,
                "AbortPolicy、CallerRunsPolicy、DiscardPolicy、DiscardOldestPolicy",
                "TEXT", 45, requestId);
    }

    private long insertEvaluation(long sessionId, long questionId, long answerId) {
        return insertAndReturnKey(
                "INSERT INTO answer_evaluation "
                        + "(session_id, question_id, answer_id, score, level, "
                        + "matched_points_json, missing_points_json, risk_points_json, next_action, "
                        + "candidate_pool_snapshot_json, selected_next_question_id, evaluation_text, model_name) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                sessionId, questionId, answerId, 85, "GOOD",
                "[\"四种拒绝策略\"]", "[\"能说明适用场景\"]", "[\"未提及CallerRunsPolicy的特点\"]",
                "FOLLOW_UP",
                "{\"followUp\":[],\"switchTopic\":[{\"questionId\":\"cand-1\",\"reason\":\"同阶段换题\"}]}",
                questionId, "回答要点完整，可继续追问适用场景", "qwen-plus");
    }

    /**
     * 将数据库返回的 JSON 值解析为 JsonNode 用于语义比较。
     *
     * H2 的 JSON 列经 JDBC 读取可能返回 byte[]（UTF-8 文本），且可能把
     * 字符串写入的值序列化为带引号/转义的 JSON 字符串（多一层包裹），
     * 因此需要归一化后再解析，确保对 H2 与 MySQL 行为一致。
     */
    private JsonNode parseJson(Object raw) throws JsonProcessingException {
        String text = raw instanceof byte[]
                ? new String((byte[]) raw, StandardCharsets.UTF_8)
                : String.valueOf(raw);
        JsonNode node = objectMapper.readTree(text);
        if (node.isTextual()) {
            // H2 把值作为 JSON 字符串存储时，再解析一层还原实际 JSON 文档
            node = objectMapper.readTree(node.asText());
        }
        return node;
    }

    /**
     * 执行插入并返回自增主键。
     * 依赖 H2 与 MySQL 自增主键语义，主键列需设为可返回生成的 key。
     */
    private long insertAndReturnKey(String sql, Object... args) {
        return jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Long>) connection -> {
            try (java.sql.PreparedStatement ps =
                         connection.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                ps.executeUpdate();
                try (java.sql.ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) {
                        return keys.getLong(1);
                    }
                }
            }
            return null;
        });
    }
}

package com.smartview.report;

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
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 报告两表迁移集成测试（V9）。
 *
 * 使用纯 H2（MySQL 兼容模式）验证 V9 迁移脚本与 Task 6.1 验收标准，
 * 不启动 Spring 上下文、不依赖外部基础设施。模式与 InterviewTablesMigrationTest
 * 一致：内联创建 V7 外键依赖的父表桩（user / resume_profile / profile_analysis），
 * 再依次应用 V7/V8/V9 迁移脚本，聚焦验证本任务交付的报告表结构与约束。
 *
 * 覆盖验收标准（报告和参考答案能关联到会话、问题和用户）：
 * 1. interview_report 与 reference_answer 可通过 JOIN 关联会话、问题、用户；
 * 2. 报告各维度 JSON 字段（优势/薄弱/风险/建议/覆盖）与参考答案要点/权衡点可读写；
 * 3. 唯一索引 uk_interview_report_session_deleted 保证一个会话最多一份有效报告，
 *    且兼容"软删除→重建"；
 * 4. 唯一索引 uk_reference_answer_report_question_deleted 保证同一报告内每道题
 *    至多一份有效参考答案；
 * 5. status 默认落库为 GENERATING，且生成失败/重试通过同一条记录原地更新
 *    （GENERATING→FAILED→GENERATING→SUCCESS），唯一索引不阻碍状态流转；
 * 6. 外键 ON DELETE CASCADE 生效：删除会话级联清理报告与参考答案。
 *
 * H2 连接参数与 InterviewTablesMigrationTest 保持一致（MODE=MySQL、大小写不敏感等）。
 */
class ReportTablesMigrationTest {

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
        dataSource.setURL("jdbc:h2:mem:sv_report_" + DB_SEQ.getAndIncrement()
                + ";MODE=MySQL;NON_KEYWORDS=USER;DATABASE_TO_LOWER=TRUE;"
                + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        jdbc = new JdbcTemplate(dataSource);

        // 内联创建 V7 外键依赖的父表桩（仅含 V7 外键所需的主键列与测试插入列）。
        // 设计取舍：不套用 V1/V2/V6 的真实结构，避免受既有 V2 在 H2 上的兼容问题影响
        // （与 InterviewTablesMigrationTest 一致）。
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

        // 依次应用 V7 + V8 + V9 迁移脚本（ResourceDatabasePopulator 按分号切分语句、剥离注释）
        ResourceDatabasePopulator populator =
                new ResourceDatabasePopulator(
                        new ClassPathResource("db/migration/V7__create_interview_tables.sql"),
                        new ClassPathResource("db/migration/V8__add_interview_answer_request_id_unique.sql"),
                        new ClassPathResource("db/migration/V9__create_report_tables.sql"));
        populator.execute(dataSource);
    }

    @Test
    void reportAndReferenceAnswerCanBeJoinedAcrossEntities() throws Exception {
        // 构造完整依赖链：user → resume_profile → profile_analysis → 会话 → 问题 → 报告 → 参考答案
        long userId = insertUser("report_tables_user");
        long resumeProfileId = insertResumeProfile(userId);
        long profileAnalysisId = insertProfileAnalysis(userId, resumeProfileId);
        long sessionId = insertSession(userId, resumeProfileId, profileAnalysisId);

        // 问题：首题为 OPENING（无父题），第二题为追问（parent 指向首题）
        long firstQuestionId = insertQuestion(sessionId, userId, 1, null, "OPENING", "线程池原理");
        long secondQuestionId = insertQuestion(sessionId, userId, 2, firstQuestionId, "FOLLOW_UP", "线程池拒绝策略");

        // 报告：生成成功（SUCCESS），携带完整评分与各维度 JSON
        long reportId = insertReport(sessionId, userId, resumeProfileId, "SUCCESS");

        // 参考答案：两题各一份（基础题关键要点 / 场景题答题框架）
        insertReferenceAnswer(reportId, sessionId, firstQuestionId, "BASIC_KEY_POINTS");
        insertReferenceAnswer(reportId, sessionId, secondQuestionId, "PROJECT_STRUCTURE");

        // 关联查询：一次 JOIN 贯穿 报告→参考答案→会话→问题→用户，验证验收标准
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT r.id AS report_id, r.session_id, r.user_id, r.resume_profile_id, "
                        + "r.overall_score, r.readiness_level, r.role_fit_score, r.summary, r.status, "
                        + "r.generated_at, "
                        + "r.strengths_json, r.weaknesses_json, r.risk_points_json, "
                        + "r.suggestions_json, r.coverage_json, "
                        + "ra.id AS ra_id, ra.question_id, ra.answer_type, ra.reference_content, "
                        + "ra.key_points_json, ra.tradeoffs_json, "
                        + "q.question_text, q.question_order, "
                        + "s.role_direction, u.username "
                        + "FROM interview_report r "
                        + "JOIN reference_answer ra ON ra.report_id = r.id "
                        + "JOIN interview_session s ON s.id = r.session_id "
                        + "JOIN interview_question q ON q.id = ra.question_id "
                        + "JOIN `user` u ON u.id = r.user_id "
                        + "WHERE r.id = ? AND ra.question_id = ?",
                reportId, secondQuestionId);

        // —— 断言报告关联字段（会话 / 用户 / 画像） ——
        assertThat(((Number) row.get("session_id")).longValue()).isEqualTo(sessionId);
        assertThat(((Number) row.get("user_id")).longValue()).isEqualTo(userId);
        assertThat(((Number) row.get("resume_profile_id")).longValue()).isEqualTo(resumeProfileId);

        // —— 断言报告评分与状态字段 ——
        assertThat(((Number) row.get("overall_score")).intValue()).isEqualTo(82);
        assertThat(row.get("readiness_level")).isEqualTo("READY");
        assertThat(((Number) row.get("role_fit_score")).intValue()).isEqualTo(75);
        assertThat(row.get("summary")).isEqualTo("整体表现良好，基础知识扎实，场景设计需加强");
        assertThat(row.get("status")).isEqualTo("SUCCESS");
        assertThat(row.get("generated_at")).isNotNull();

        // —— 断言报告各维度 JSON 字段（语义级比较） ——
        JsonNode strengths = parseJson(row.get("strengths_json"));
        assertThat(strengths).hasSize(2);
        assertThat(strengths.get(0).asText()).isEqualTo("项目架构清晰");

        JsonNode weaknesses = parseJson(row.get("weaknesses_json"));
        assertThat(weaknesses).hasSize(1);

        JsonNode riskPoints = parseJson(row.get("risk_points_json"));
        assertThat(riskPoints.get(0).asText()).contains("高并发");

        JsonNode suggestions = parseJson(row.get("suggestions_json"));
        assertThat(suggestions).hasSize(1);
        assertThat(suggestions.get(0).path("topic").asText()).isEqualTo("系统设计");

        JsonNode coverage = parseJson(row.get("coverage_json"));
        assertThat(coverage.path("basicCoverage").asDouble()).isEqualTo(0.9);
        assertThat(coverage.path("scenarioCoverage").asDouble()).isEqualTo(0.6);

        // —— 断言参考答案字段（类型 / 正文 / 要点 / 权衡点） ——
        assertThat(((Number) row.get("ra_id")).longValue()).isGreaterThan(0);
        assertThat(row.get("answer_type")).isEqualTo("PROJECT_STRUCTURE");
        assertThat(row.get("reference_content")).asString().contains("PROJECT_STRUCTURE");

        JsonNode keyPoints = parseJson(row.get("key_points_json"));
        assertThat(keyPoints).hasSize(2);

        JsonNode tradeoffs = parseJson(row.get("tradeoffs_json"));
        assertThat(tradeoffs).hasSize(1);
        assertThat(tradeoffs.get(0).path("aspect").asText()).isEqualTo("一致性");

        // —— 断言关联到问题与会话（验收标准核心：报告/参考答案能关联会话与问题） ——
        assertThat(((Number) row.get("question_id")).longValue()).isEqualTo(secondQuestionId);
        assertThat(row.get("question_text")).isEqualTo("请说明线程池拒绝策略有哪些要点？");
        assertThat(((Number) row.get("question_order")).intValue()).isEqualTo(2);
        assertThat(row.get("role_direction")).isEqualTo("JAVA_BACKEND");
        assertThat(row.get("username")).isEqualTo("report_tables_user");
    }

    @Test
    void onlyOneValidReportAllowedPerSession() {
        // 构造最小依赖链
        long userId = insertUser("report_tables_unique_user");
        long resumeProfileId = insertResumeProfile(userId);
        long profileAnalysisId = insertProfileAnalysis(userId, resumeProfileId);
        long sessionId = insertSession(userId, resumeProfileId, profileAnalysisId);

        // 首次生成报告（deleted=0）
        long reportId = insertReport(sessionId, userId, resumeProfileId, "GENERATING");

        // 同一会话再次生成有效报告必须被唯一索引拦截（防重复生成报告）
        assertThatThrownBy(() -> insertReport(sessionId, userId, resumeProfileId, "GENERATING"))
                .isInstanceOf(DataIntegrityViolationException.class);

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM interview_report WHERE session_id = ? AND deleted = 0",
                Integer.class, sessionId);
        assertThat(count).isEqualTo(1);

        // 软删除后重建有效报告：旧报告置 deleted=1，新报告可正常插入（兼容重建）
        jdbc.update("UPDATE interview_report SET deleted = 1 WHERE id = ?", reportId);
        long rebuiltId = insertReport(sessionId, userId, resumeProfileId, "SUCCESS");
        assertThat(rebuiltId).isGreaterThan(reportId);

        Integer activeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM interview_report WHERE session_id = ? AND deleted = 0",
                Integer.class, sessionId);
        assertThat(activeCount).isEqualTo(1);
    }

    @Test
    void reportStatusDefaultsToGeneratingAndRetryUpdatesInPlace() {
        // 构造最小依赖链
        long userId = insertUser("report_tables_status_user");
        long resumeProfileId = insertResumeProfile(userId);
        long profileAnalysisId = insertProfileAnalysis(userId, resumeProfileId);
        long sessionId = insertSession(userId, resumeProfileId, profileAnalysisId);

        // 未显式指定 status 时落库为默认值 GENERATING（DDL 默认值）
        long reportId = insertAndReturnKey(
                "INSERT INTO interview_report (session_id, user_id, resume_profile_id) VALUES (?, ?, ?)",
                sessionId, userId, resumeProfileId);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM interview_report WHERE id = ?", String.class, reportId))
                .isEqualTo("GENERATING");

        // 报告生成失败/重试走同一条记录原地更新（GENERATING→FAILED→GENERATING→SUCCESS），
        // 无需也不允许另插新行：唯一索引仅拦截"同一会话第二条有效报告"，
        // 不阻止同一条记录的状态流转（DDL 注释声明的核心状态机设计点）。
        jdbc.update("UPDATE interview_report SET status = ? WHERE id = ?", "FAILED", reportId);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM interview_report WHERE id = ?", String.class, reportId))
                .isEqualTo("FAILED");
        jdbc.update("UPDATE interview_report SET status = ? WHERE id = ?", "GENERATING", reportId);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM interview_report WHERE id = ?", String.class, reportId))
                .isEqualTo("GENERATING");
        jdbc.update("UPDATE interview_report SET status = ?, generated_at = CURRENT_TIMESTAMP WHERE id = ?",
                "SUCCESS", reportId);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM interview_report WHERE id = ?", String.class, reportId))
                .isEqualTo("SUCCESS");

        // 原地更新后仍是同一行，未产生第二条有效报告
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM interview_report WHERE session_id = ? AND deleted = 0",
                Integer.class, sessionId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void onlyOneValidReferenceAnswerAllowedPerReportQuestion() {
        // 构造完整依赖链
        long userId = insertUser("report_tables_ra_user");
        long resumeProfileId = insertResumeProfile(userId);
        long profileAnalysisId = insertProfileAnalysis(userId, resumeProfileId);
        long sessionId = insertSession(userId, resumeProfileId, profileAnalysisId);
        long q1 = insertQuestion(sessionId, userId, 1, null, "OPENING", "JVM内存模型");
        long q2 = insertQuestion(sessionId, userId, 2, null, "OPENING", "并发工具");
        long reportId = insertReport(sessionId, userId, resumeProfileId, "SUCCESS");

        insertReferenceAnswer(reportId, sessionId, q1, "BASIC_KEY_POINTS");
        // 同一报告内不同问题可以各有一份参考答案
        insertReferenceAnswer(reportId, sessionId, q2, "SCENARIO_FRAMEWORK");

        // 同一报告内同一问题再次插入必须被唯一索引拦截（防重复生成时同题多答）
        assertThatThrownBy(() -> insertReferenceAnswer(reportId, sessionId, q1, "BASIC_KEY_POINTS"))
                .isInstanceOf(DataIntegrityViolationException.class);

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reference_answer WHERE report_id = ? AND question_id = ?",
                Integer.class, reportId, q1);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void reportAndReferenceAnswerCascadeOnSessionDelete() {
        // 构造完整依赖链
        long userId = insertUser("report_tables_cascade_user");
        long resumeProfileId = insertResumeProfile(userId);
        long profileAnalysisId = insertProfileAnalysis(userId, resumeProfileId);
        long sessionId = insertSession(userId, resumeProfileId, profileAnalysisId);
        long questionId = insertQuestion(sessionId, userId, 1, null, "OPENING", "HashMap原理");
        long reportId = insertReport(sessionId, userId, resumeProfileId, "SUCCESS");
        insertReferenceAnswer(reportId, sessionId, questionId, "BASIC_KEY_POINTS");

        // 硬删除会话：报告与参考答案应通过外键 ON DELETE CASCADE 级联清理
        jdbc.update("DELETE FROM interview_session WHERE id = ?", sessionId);

        Integer reportCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM interview_report WHERE session_id = ?", Integer.class, sessionId);
        assertThat(reportCount).isZero();

        Integer answerCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reference_answer WHERE session_id = ?", Integer.class, sessionId);
        assertThat(answerCount).isZero();
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
                userId, resumeProfileId, profileAnalysisId, "JAVA_BACKEND", "COMPLETED",
                2, 8, 20,
                "{\"stages\":[\"BASIC\",\"PROJECT\",\"SCENARIO\"],\"totalMin\":8}",
                "{\"BASIC\":{\"questionCount\":2,\"coveredTopics\":[\"线程池\"]}}",
                "thread-report-test-0001", "checkpoint-report-test-0001", 1);
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
                "[{\"knowledgeId\":\"kb-1\",\"snippet\":\"线程池\"}]",
                "[{\"caseId\":\"case-1\"}]",
                "[\"掌握核心要点\",\"能说明适用场景\"]",
                "ASKED");
    }

    /**
     * 插入一条报告记录，携带完整评分与各维度 JSON 字段。
     *
     * @param status 报告状态（GENERATING / SUCCESS / FAILED），由用例按需传入
     */
    private long insertReport(long sessionId, long userId, long resumeProfileId, String status) {
        return insertAndReturnKey(
                "INSERT INTO interview_report "
                        + "(session_id, user_id, resume_profile_id, overall_score, readiness_level, "
                        + "role_fit_score, summary, strengths_json, weaknesses_json, risk_points_json, "
                        + "suggestions_json, coverage_json, status, generated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                sessionId, userId, resumeProfileId, 82, "READY",
                75, "整体表现良好，基础知识扎实，场景设计需加强",
                "[\"项目架构清晰\",\"基础知识扎实\"]",
                "[\"场景设计经验不足\"]",
                "[\"高并发场景回答深度不够\"]",
                "[{\"topic\":\"系统设计\",\"reason\":\"场景题得分偏低\",\"resources\":[\"DDIA\"]}]",
                "{\"basicCoverage\":0.9,\"projectCoverage\":0.75,\"scenarioCoverage\":0.6}",
                status);
    }

    private long insertReferenceAnswer(long reportId, long sessionId, long questionId, String answerType) {
        return insertAndReturnKey(
                "INSERT INTO reference_answer "
                        + "(report_id, session_id, question_id, answer_type, reference_content, "
                        + "key_points_json, tradeoffs_json) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                reportId, sessionId, questionId, answerType,
                "参考答案正文：" + answerType,
                "[\"要点一\",\"要点二\"]",
                "[{\"aspect\":\"一致性\",\"options\":[\"强一致\",\"最终一致\"]}]");
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

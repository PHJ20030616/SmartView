package com.smartview.observability;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LLM 调用日志表迁移集成测试（V10 建表 + V11 诊断字段）。
 *
 * 使用纯 H2（MySQL 兼容模式）验证 V10 迁移脚本，不启动 Spring 上下文、不依赖外部基础设施，
 * 模式与 ReportTablesMigrationTest 一致。本表没有外键依赖，因此无需内联父表桩。
 *
 * 覆盖的验收要点：
 * 1. 表结构可按最小字段集插入，且 provider / retry_attempt / attempt_no / created_at 有正确默认值；
 * 2. scene / model / latency_ms / status 为必填，缺失时数据库拒绝写入
 *    （否则会出现无法归因的观测数据）；
 * 3. 不建立任何外键约束——这是"日志行必须能独立存活"的有意取舍；
 * 4. 四个查询索引存在，避免看板查询退化为全表扫描；
 * 5. V11 新增的诊断字段（http_status / finish_reason / attempt_no）可写入并读回，
 *    历史数据（迁移前写入的行）该三列为 NULL 或默认 0，不需要回填。
 */
class LlmCallLogMigrationTest {

    /** 为每个用例分配独立的 H2 内存库名，避免用例间相互污染。 */
    private static final AtomicInteger DB_SEQ = new AtomicInteger(1);

    private JdbcTemplate jdbc;

    /** 保留数据源引用：索引与外键的存在性只能通过 JDBC 元数据验证，无法用插入数据的方式断言。 */
    private JdbcDataSource dataSource;

    @BeforeEach
    void applyMigrationsOnFreshH2() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:sv_llm_log_" + DB_SEQ.getAndIncrement()
                + ";MODE=MySQL;NON_KEYWORDS=USER;DATABASE_TO_LOWER=TRUE;"
                + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        jdbc = new JdbcTemplate(dataSource);
        // 按 Flyway 的顺序应用脚本：V11 是 ALTER，必须在 V10 建表之后执行
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V10__create_llm_call_log_table.sql"),
                new ClassPathResource("db/migration/V11__extend_llm_call_log_diagnostics.sql")
        ).execute(dataSource);
    }

    @Test
    void insertWithMinimalColumnsAppliesDefaults() {
        jdbc.update("INSERT INTO `llm_call_log` (`scene`, `model`, `latency_ms`, `status`) "
                + "VALUES (?, ?, ?, ?)", "evaluate", "deepseek-v4-flash", 1234, "SUCCESS");

        var row = jdbc.queryForMap("SELECT provider, retry_attempt, attempt_no, created_at, trace_id "
                + "FROM `llm_call_log`");

        assertThat(row.get("provider")).isEqualTo("deepseek");
        assertThat(((Number) row.get("retry_attempt")).intValue()).isZero();
        // attempt_no 与 retry_attempt 是两个维度：任务重试轮次默认 0（首次处理）
        assertThat(((Number) row.get("attempt_no")).intValue()).isZero();
        assertThat(row.get("created_at")).isNotNull();
        assertThat(row.get("trace_id")).isNull();
    }

    @Test
    void diagnosticColumnsRoundTripAndDefaultForLegacyRows() {
        // 新写入的诊断字段必须能读回：看板据此区分"限流可重试"与"请求被拒"
        jdbc.update("INSERT INTO `llm_call_log` "
                        + "(`scene`, `model`, `latency_ms`, `status`, `error_code`, "
                        + "`http_status`, `finish_reason`, `attempt_no`) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                "resume_parse", "deepseek-flash", 812, "FAILED", "LLM_REQUEST_REJECTED",
                400, "stop", 2);

        var row = jdbc.queryForMap("SELECT http_status, finish_reason, attempt_no FROM `llm_call_log`");

        assertThat(((Number) row.get("http_status")).intValue()).isEqualTo(400);
        assertThat(row.get("finish_reason")).isEqualTo("stop");
        assertThat(((Number) row.get("attempt_no")).intValue()).isEqualTo(2);

        // 模拟迁移前写入的历史行：诊断三列未提供时必须可空 + 默认 0，不需要数据回填
        jdbc.update("INSERT INTO `llm_call_log` (`scene`, `model`, `latency_ms`, `status`) "
                + "VALUES (?, ?, ?, ?)", "evaluate", "deepseek-flash", 10, "SUCCESS");
        var legacy = jdbc.queryForMap("SELECT http_status, finish_reason, attempt_no "
                + "FROM `llm_call_log` WHERE `scene` = 'evaluate'");

        assertThat(legacy.get("http_status")).isNull();
        assertThat(legacy.get("finish_reason")).isNull();
        assertThat(((Number) legacy.get("attempt_no")).intValue()).isZero();
    }

    @Test
    void requiredColumnsRejectNull() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO `llm_call_log` (`model`, `latency_ms`, `status`) VALUES (?, ?, ?)",
                "deepseek-v4-flash", 10, "SUCCESS"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO `llm_call_log` (`scene`, `model`, `status`) VALUES (?, ?, ?)",
                "evaluate", "deepseek-v4-flash", "SUCCESS"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO `llm_call_log` (`scene`, `model`, `latency_ms`) VALUES (?, ?, ?)",
                "evaluate", "deepseek-v4-flash", 10))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void tableHasNoForeignKeyConstraints() throws Exception {
        // 允许悬空引用是有意设计：删用户不该抹掉历史成本与成功率数据。
        // 这里用 JDBC 标准的 DatabaseMetaData 而不是 INFORMATION_SCHEMA：
        // 前者结果集结构由 JDBC 规范固定，H2 与 MySQL 一致；后者的列名两者并不相同。
        // 现有迁移测试没有查元数据的先例，因此选择规范更稳的那条路。
        try (Connection connection = dataSource.getConnection();
             ResultSet importedKeys = connection.getMetaData()
                     .getImportedKeys(null, null, "llm_call_log")) {
            assertThat(importedKeys.next()).isFalse();
        }
    }

    @Test
    void queryIndexesAreCreated() throws Exception {
        List<String> indexNames = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             ResultSet indexes = connection.getMetaData()
                     .getIndexInfo(null, null, "llm_call_log", false, false)) {
            while (indexes.next()) {
                indexNames.add(indexes.getString("INDEX_NAME"));
            }
        }

        assertThat(indexNames)
                .contains("idx_llm_call_log_created_at")
                .contains("idx_llm_call_log_scene_prompt_created")
                .contains("idx_llm_call_log_trace_id")
                // V11 新增：按业务对象聚合调用成本（哪个会话最贵）
                .contains("idx_llm_call_log_biz_created");
    }
}

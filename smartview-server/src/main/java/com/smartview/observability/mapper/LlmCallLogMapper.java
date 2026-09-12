package com.smartview.observability.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartview.observability.entity.LlmCallLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * LLM 调用日志 Mapper。
 *
 * 只读 Mapper：写入方是 FastAPI。分页列表走 BaseMapper 的 QueryWrapper，
 * 聚合统计与 P95 需要自定义 SQL（BaseMapper 不提供聚合能力）。
 */
@Mapper
public interface LlmCallLogMapper extends BaseMapper<LlmCallLog> {

    /**
     * 按过滤条件统计调用总数、成功数与 token 合计。
     *
     * 统计口径覆盖过滤条件下的**全部**记录，与分页无关——看板上的成功率与成本
     * 不应随翻页变化。
     */
    @Select("""
            <script>
            SELECT COUNT(*) AS totalCalls,
                   COALESCE(SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END), 0) AS successCalls,
                   COALESCE(SUM(token_total), 0) AS totalTokens
            FROM llm_call_log
            <where>
                <if test="scene != null and scene != ''">AND scene = #{scene}</if>
                <if test="promptVersion != null and promptVersion != ''">AND prompt_version = #{promptVersion}</if>
                <if test="from != null">AND created_at &gt;= #{from}</if>
                <if test="to != null">AND created_at &lt; #{to}</if>
            </where>
            </script>
            """)
    Map<String, Object> selectStats(@Param("scene") String scene,
                                    @Param("promptVersion") String promptVersion,
                                    @Param("from") LocalDateTime from,
                                    @Param("to") LocalDateTime to);

    /**
     * 取按延迟升序排列第 offset 条记录的延迟值，用于计算 P95。
     *
     * MySQL 8 没有 PERCENTILE_CONT（MySQL 未实现该 OLAP 函数），因此用
     * "排序 + OFFSET" 实现等价结果：offset = FLOOR(总条数 * 0.95)，由调用方依据
     * selectStats 的 totalCalls 计算，避免在 SQL 里再套一层子查询。
     * 返回 null 表示过滤条件下没有记录。
     */
    @Select("""
            <script>
            SELECT latency_ms
            FROM llm_call_log
            <where>
                <if test="scene != null and scene != ''">AND scene = #{scene}</if>
                <if test="promptVersion != null and promptVersion != ''">AND prompt_version = #{promptVersion}</if>
                <if test="from != null">AND created_at &gt;= #{from}</if>
                <if test="to != null">AND created_at &lt; #{to}</if>
            </where>
            ORDER BY latency_ms
            LIMIT 1 OFFSET #{offset}
            </script>
            """)
    Integer selectLatencyAtOffset(@Param("scene") String scene,
                                  @Param("promptVersion") String promptVersion,
                                  @Param("from") LocalDateTime from,
                                  @Param("to") LocalDateTime to,
                                  @Param("offset") int offset);
}

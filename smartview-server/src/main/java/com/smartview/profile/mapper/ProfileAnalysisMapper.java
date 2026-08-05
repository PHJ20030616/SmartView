package com.smartview.profile.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartview.profile.entity.ProfileAnalysis;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 画像分析结果 Mapper 接口
 *
 * 功能说明：
 * - 提供 profile_analysis 表的 CRUD 操作
 * - 继承 MyBatis-Plus 的 BaseMapper，自动获得基础方法，无需编写 XML
 * - MyBatis-Plus 自动处理逻辑删除（deleted 字段），查询时自动过滤 deleted=1
 * - 字段自动填充由 MyMetaObjectHandler 处理（createdAt、updatedAt）
 *
 * 唯一约束说明：
 * - 数据库唯一索引 (resume_profile_id, role_direction, profile_version)
 *   保证同一简历版本、同一面试方向只有一份有效画像分析；
 * - 业务层处理结果时按唯一键先查后插/更新，避免重复写入冲突。
 *
 * @author SmartView Team
 * @since 2026-08-03
 */
@Mapper
public interface ProfileAnalysisMapper extends BaseMapper<ProfileAnalysis> {

    /**
     * 按唯一键查询画像分析行，不受逻辑删除过滤。
     *
     * 业务层结果消费时使用自定义 SQL 绕过 @TableLogic 的 deleted=0 过滤：
     * 若该键存在 deleted=1 的软删除行，仍能查到并复活（置 deleted=0 后覆盖），
     * 避免唯一索引与软删除过滤冲突导致同键重分析永久失败。
     */
    @Select("SELECT id, user_id, resume_profile_id, role_direction, "
            + "skill_tags_json, project_graph_json, capability_hints_json, "
            + "risk_points_json, suggested_topics_json, stage_targets_json, "
            + "profile_version, model_name, model_version, created_at, updated_at, deleted "
            + "FROM profile_analysis "
            + "WHERE resume_profile_id = #{resumeProfileId} "
            + "AND role_direction = #{roleDirection} "
            + "AND profile_version = #{profileVersion} "
            + "LIMIT 1")
    ProfileAnalysis selectForUpsert(
            @Param("resumeProfileId") Long resumeProfileId,
            @Param("roleDirection") String roleDirection,
            @Param("profileVersion") Integer profileVersion);
}

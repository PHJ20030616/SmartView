package com.smartview.resume.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 触发方向画像分析请求。
 *
 * 隔离条件由 Spring 根据认证用户和请求中的画像 ID 生成，前端不能提交
 * user_id、profile_version 等字段参与查询。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StartProfileAnalysisRequest {

    /**
     * 简历画像 ID
     */
    private Long profileId;

    /**
     * 面试方向：JAVA_BACKEND / AGENT_DEVELOPMENT
     */
    private String roleDirection;
}

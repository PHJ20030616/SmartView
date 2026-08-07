package com.smartview.ai.client;

import com.smartview.interview.model.CandidatePoolItem;
import lombok.Data;

import java.util.List;

/**
 * FastAPI 候选池生成响应模型（FastAPI → Spring Boot）。
 *
 * 功能说明：
 * - 对应 ai-api 契约的 GenerateCandidatePoolResponse；candidates 直接复用
 *   CandidatePoolItem（Redis 存储与 FollowUpPoolService 共用同一结构，避免重复 DTO）
 * - success=false 时读取 errorMessage 提示用户
 *
 * @author SmartView Team
 * @since 2026-08-07
 */
@Data
public class AiGenerateCandidatePoolResponse {

    /** 生成是否成功 */
    private Boolean success;

    /** 候选问题列表（0-4 道） */
    private List<CandidatePoolItem> candidates;

    /** 生成失败原因 */
    private String errorMessage;
}

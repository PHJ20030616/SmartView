package com.smartview.ai.client;

import com.smartview.interview.model.CandidatePoolItem;
import lombok.Data;

import java.util.List;

/**
 * FastAPI 回答评估响应模型（FastAPI → Spring Boot）。
 *
 * 功能说明：
 * - 对应 ai-api 契约的 EvaluateAnswerResponse：评估事实（得分/等级/命中/缺失/风险）
 *   + 追问候选池（0-2 道）
 * - followUpCandidates 复用 CandidatePoolItem（与 Redis 存储、StagePolicyEngine 决策
 *   共用同一结构，避免为同一数据维护多份重复 DTO）
 * - success=false 时读取 errorMessage 提示用户，调用方应允许重试而不落库
 *
 * @author SmartView Team
 * @since 2026-08-09
 */
@Data
public class AiEvaluateAnswerResponse {

    /** 评估是否成功 */
    private Boolean success;

    /** 回答得分 0-100 */
    private Integer score;

    /** 回答等级 GOOD / NORMAL / WEAK */
    private String level;

    /** 命中要点 */
    private List<String> matchedPoints;

    /** 缺失要点 */
    private List<String> missingPoints;

    /** 风险点（对象数组，含 category/description） */
    private List<Object> riskPoints;

    /** 追问候选池（0-2 道，完整 CandidatePoolItem） */
    private List<CandidatePoolItem> followUpCandidates;

    /** 评估失败原因 */
    private String errorMessage;
}

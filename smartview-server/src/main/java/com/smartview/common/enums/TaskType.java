package com.smartview.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * AI 任务类型枚举
 *
 * 用于标识不同类型的 AI 任务，不同类型可能对应不同的处理流程和超时配置
 *
 * 业务说明：
 * - RESUME_PARSE：简历解析任务，从 PDF 提取文本并结构化
 * - PROFILE_ANALYZE：画像分析任务，基于简历画像生成能力评估
 * - REPORT_GENERATE：报告生成任务，生成面试报告、匹配度报告等
 * - CLEANUP：清理任务，定期清理过期数据
 *
 * @author SmartView Team
 * @since 2026-07-23
 */
@Getter
@AllArgsConstructor
public enum TaskType {

    /**
     * 简历解析
     * 从 PDF 文件提取文本并结构化解析为简历画像
     * 涉及 OCR、NLP、信息抽取等技术
     */
    RESUME_PARSE("RESUME_PARSE", "简历解析"),

    /**
     * 画像分析
     * 基于简历画像生成候选人能力评估、职业发展建议等
     */
    PROFILE_ANALYZE("PROFILE_ANALYZE", "画像分析"),

    /**
     * 报告生成
     * 生成各类报告，如面试评估报告、职位匹配度报告等
     */
    REPORT_GENERATE("REPORT_GENERATE", "报告生成"),

    /**
     * 清理任务
     * 定期清理过期数据、临时文件等
     */
    CLEANUP("CLEANUP", "清理任务");

    /**
     * 任务类型代码，与数据库字段值一致
     */
    private final String code;

    /**
     * 任务类型描述，用于前端展示
     */
    private final String description;

    /**
     * 根据任务类型代码获取枚举实例
     *
     * @param code 任务类型代码
     * @return 对应的枚举实例，不存在则返回 null
     */
    public static TaskType fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (TaskType type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return null;
    }
}

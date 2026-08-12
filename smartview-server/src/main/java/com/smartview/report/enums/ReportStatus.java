package com.smartview.report.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 面试报告状态枚举。
 *
 * 与 interview_report.status 存储值及 web-api 契约一致。报告状态独立于会话状态
 * （plan 5.4）：报告失败不得把已结束的面试改成失败会话。
 */
@Getter
@AllArgsConstructor
public enum ReportStatus {

    /** 生成中：会话结束已创建报告行，等待 FastAPI 生成 */
    GENERATING("GENERATING", "生成中"),

    /** 生成成功：报告内容与参考答案已落库 */
    SUCCESS("SUCCESS", "生成成功"),

    /** 生成失败：报告生成任务终态失败 */
    FAILED("FAILED", "生成失败");

    private final String code;
    private final String description;

    /** 根据状态代码获取枚举实例，不存在返回 null */
    public static ReportStatus fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (ReportStatus status : values()) {
            if (status.getCode().equals(code)) {
                return status;
            }
        }
        return null;
    }
}

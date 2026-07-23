package com.smartview.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 确认状态枚举
 *
 * 用于标识用户对AI解析结果的确认状态
 *
 * 业务规则：
 * - AI 解析完成后，画像初始状态为 UNCONFIRMED
 * - 用户查看并编辑画像后，点击"确认"按钮，状态更新为 CONFIRMED
 * - 只有 CONFIRMED 状态的画像才能用于后续的职位匹配、简历推荐等业务
 * - 用户可以多次编辑未确认的画像，但一旦确认后不建议频繁修改
 *
 * @author SmartView Team
 * @since 2026-07-23
 */
@Getter
@AllArgsConstructor
public enum ConfirmStatus {

    /**
     * 未确认
     * AI 刚生成画像，用户尚未查看或编辑
     * 可能包含 AI 解析错误，需要用户人工校验
     */
    UNCONFIRMED("UNCONFIRMED", "未确认"),

    /**
     * 已确认
     * 用户已查看并确认画像内容准确
     * 可以用于职位匹配、简历推荐等后续业务
     */
    CONFIRMED("CONFIRMED", "已确认");

    /**
     * 状态代码，与数据库字段值一致
     */
    private final String code;

    /**
     * 状态描述，用于前端展示
     */
    private final String description;

    /**
     * 根据状态代码获取枚举实例
     *
     * @param code 状态代码
     * @return 对应的枚举实例，不存在则返回 null
     */
    public static ConfirmStatus fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (ConfirmStatus status : values()) {
            if (status.getCode().equals(code)) {
                return status;
            }
        }
        return null;
    }
}

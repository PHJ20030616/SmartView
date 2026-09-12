package com.smartview.observability.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * LLM 调用观测接口的访问配置。
 *
 * 这里刻意用"用户名白名单"而不是角色体系：plan_1.1 明确把多租户 RBAC 列为非目标，
 * 而这个接口是跨用户的运维视图，必须有最基本的访问控制。
 * 默认值取空列表 = 接口对所有人关闭，遵循默认安全原则。
 */
@ConfigurationProperties(prefix = "smartview.llm-call-log")
public class LlmCallLogProperties {

    /** 允许访问观测接口的用户名列表；为空表示接口关闭。 */
    private List<String> operatorUsernames = List.of();

    /**
     * 判断指定用户是否在运维白名单内。
     *
     * 用户名为 null（未认证）时一律返回 false，不抛异常：
     * 调用方需要自己决定"未登录"应该返回 401 还是 403。
     */
    public boolean isOperator(String username) {
        return username != null && operatorUsernames.contains(username);
    }

    public List<String> getOperatorUsernames() {
        return operatorUsernames;
    }

    public void setOperatorUsernames(List<String> operatorUsernames) {
        this.operatorUsernames = operatorUsernames == null ? List.of() : List.copyOf(operatorUsernames);
    }
}

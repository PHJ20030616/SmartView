package com.smartview.observability;

import com.smartview.observability.config.LlmCallLogProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 观测接口白名单语义测试。
 *
 * 重点验证"默认关闭"：默认值下任何用户都不得通过，否则一个漏配的部署环境
 * 就会把全体用户的调用元数据暴露给任意登录用户。
 */
class LlmCallLogPropertiesTest {

    @Test
    void emptyWhitelistDeniesEveryone() {
        LlmCallLogProperties properties = new LlmCallLogProperties();

        assertThat(properties.isOperator("anyone")).isFalse();
        assertThat(properties.isOperator(null)).isFalse();
    }

    @Test
    void whitelistedUsernameIsAllowed() {
        LlmCallLogProperties properties = new LlmCallLogProperties();
        properties.setOperatorUsernames(List.of("ops", "admin"));

        assertThat(properties.isOperator("ops")).isTrue();
        assertThat(properties.isOperator("admin")).isTrue();
        assertThat(properties.isOperator("normal-user")).isFalse();
    }

    @Test
    void nullAssignmentFallsBackToEmptyWhitelist() {
        LlmCallLogProperties properties = new LlmCallLogProperties();
        properties.setOperatorUsernames(null);

        assertThat(properties.getOperatorUsernames()).isEmpty();
        assertThat(properties.isOperator("anyone")).isFalse();
    }
}

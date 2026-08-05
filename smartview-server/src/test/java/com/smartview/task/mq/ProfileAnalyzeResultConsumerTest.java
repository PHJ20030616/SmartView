package com.smartview.task.mq;

import com.smartview.common.exception.BusinessException;
import com.smartview.resume.service.ProfileAnalysisTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * 画像分析结果消费者测试：验证委托、业务异常进死信、系统异常重试三种分支。
 */
@ExtendWith(MockitoExtension.class)
class ProfileAnalyzeResultConsumerTest {

    @Mock
    private ProfileAnalysisTaskService profileAnalysisTaskService;

    private ProfileAnalyzeResultConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new ProfileAnalyzeResultConsumer(profileAnalysisTaskService);
    }

    @Test
    void handleProfileAnalyzeResult_shouldDelegateToServiceWhenSuccess() {
        ProfileAnalyzeResultMessage message = ProfileAnalyzeResultMessage.builder()
                .taskId("task-001")
                .resumeProfileId("12")
                .profileVersion(2)
                .roleDirection("JAVA_BACKEND")
                .success(true)
                .build();

        assertThatCode(() -> consumer.handleProfileAnalyzeResult(message))
                .doesNotThrowAnyException();

        verify(profileAnalysisTaskService).handleResult(message);
    }

    @Test
    void handleProfileAnalyzeResult_shouldRejectBusinessExceptionToDeadLetterQueue() {
        ProfileAnalyzeResultMessage message = ProfileAnalyzeResultMessage.builder()
                .taskId("task-002")
                .resumeProfileId("12")
                .profileVersion(2)
                .roleDirection("JAVA_BACKEND")
                .success(true)
                .build();
        doThrow(new BusinessException("结果校验失败"))
                .when(profileAnalysisTaskService).handleResult(any());

        // 业务校验失败不能 ACK，否则任务会永久停留在处理中；进入 DLQ 前需收口任务。
        assertThatThrownBy(() -> consumer.handleProfileAnalyzeResult(message))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasCauseInstanceOf(BusinessException.class);
        verify(profileAnalysisTaskService).markResultHandlingFailed(anyString(), anyString());
    }

    @Test
    void handleProfileAnalyzeResult_shouldRethrowOnRuntimeException() {
        ProfileAnalyzeResultMessage message = ProfileAnalyzeResultMessage.builder()
                .taskId("task-003")
                .resumeProfileId("12")
                .profileVersion(2)
                .roleDirection("JAVA_BACKEND")
                .success(true)
                .build();
        doThrow(new RuntimeException("数据库连接失败"))
                .when(profileAnalysisTaskService).handleResult(any());

        // 系统异常应重新抛出，触发 RabbitMQ 有界重试。
        assertThatThrownBy(() -> consumer.handleProfileAnalyzeResult(message))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("等待重试");
    }
}

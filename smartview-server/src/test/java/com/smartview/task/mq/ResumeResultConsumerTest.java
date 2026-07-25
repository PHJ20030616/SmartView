package com.smartview.task.mq;

import com.smartview.common.exception.BusinessException;
import com.smartview.resume.service.ResumeProfileService;
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

@ExtendWith(MockitoExtension.class)
class ResumeResultConsumerTest {

    @Mock
    private ResumeProfileService resumeProfileService;

    private ResumeResultConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new ResumeResultConsumer(resumeProfileService);
    }

    @Test
    void handleResumeParseResult_shouldDelegateToServiceWhenSuccess() {
        ResumeParseResultMessage message = ResumeParseResultMessage.builder()
                .taskId("task-001")
                .resumeFileId("1")
                .success(true)
                .build();

        assertThatCode(() -> consumer.handleResumeParseResult(message))
                .doesNotThrowAnyException();

        verify(resumeProfileService).handleResult(message);
    }

    @Test
    void handleResumeParseResult_shouldRejectBusinessExceptionToDeadLetterQueue() {
        ResumeParseResultMessage message = ResumeParseResultMessage.builder()
                .taskId("task-002")
                .resumeFileId("1")
                .success(true)
                .build();
        doThrow(new BusinessException("消息校验失败"))
                .when(resumeProfileService).handleResult(any());

        // 业务校验失败不能 ACK，否则任务和文件会永久停留在处理中。
        assertThatThrownBy(() -> consumer.handleResumeParseResult(message))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasCauseInstanceOf(BusinessException.class);
        verify(resumeProfileService).markResultHandlingFailed(any(), anyString());
    }

    @Test
    void handleResumeParseResult_shouldRethrowOnRuntimeException() {
        ResumeParseResultMessage message = ResumeParseResultMessage.builder()
                .taskId("task-003")
                .resumeFileId("1")
                .success(true)
                .build();
        doThrow(new RuntimeException("数据库连接失败"))
                .when(resumeProfileService).handleResult(any());

        // 系统异常应重新抛出，触发 RabbitMQ 重试
        assertThatThrownBy(() -> consumer.handleResumeParseResult(message))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("等待重试");
    }
}

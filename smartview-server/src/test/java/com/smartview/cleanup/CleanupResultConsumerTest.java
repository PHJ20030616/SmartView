package com.smartview.cleanup;

import com.smartview.common.enums.BizType;
import com.smartview.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 清理结果消费者测试（Task 7.2）。
 *
 * 覆盖：成功消费委托服务更新任务、业务校验失败收口为终态失败并进入 DLQ、
 * 基础设施异常重新抛出等待 MQ 有限重试。
 */
@ExtendWith(MockitoExtension.class)
class CleanupResultConsumerTest {

    @Mock
    private CleanupTaskService cleanupTaskService;

    private CleanupResultConsumer consumer;

    private CleanupResultMessage validResult() {
        return CleanupResultMessage.builder()
                .taskId("t1")
                .traceId("trace-1")
                .messageType("CLEANUP_RESULT")
                .schemaVersion("1.0.0")
                .retryCount(0)
                // 结果消息 createdAt 为 RFC 3339 字符串（与生产 worker 透传一致）
                .createdAt(LocalDateTime.now().toString())
                .bizType(BizType.RESUME_FILE.getCode())
                .bizId("88")
                .success(true)
                .cleanedProfileCount(2)
                .build();
    }

    @Test
    void handleCleanupResult_delegatesToServiceOnSuccess() {
        consumer = new CleanupResultConsumer(cleanupTaskService);

        consumer.handleCleanupResult(validResult());

        verify(cleanupTaskService).handleResult(any(CleanupResultMessage.class));
    }

    @Test
    void handleCleanupResult_marksHandlingFailedAndRejectsOnBusinessError() {
        consumer = new CleanupResultConsumer(cleanupTaskService);
        doThrow(new BusinessException("清理结果与任务业务不匹配"))
                .when(cleanupTaskService).handleResult(any(CleanupResultMessage.class));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> consumer.handleCleanupResult(validResult()))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        // 消息进入 DLQ 前先把任务收口为终态失败，避免清理任务永久停留在非终态
        verify(cleanupTaskService).markResultHandlingFailed(eq("t1"), anyString());
    }

    @Test
    void handleCleanupResult_rethrowsInfrastructureErrorForMqRetry() {
        consumer = new CleanupResultConsumer(cleanupTaskService);
        doThrow(new RuntimeException("数据库连接中断"))
                .when(cleanupTaskService).handleResult(any(CleanupResultMessage.class));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> consumer.handleCleanupResult(validResult()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("等待重试");

        // 基础设施异常由 MQ 容器有限重试处理，不主动标记失败
        verify(cleanupTaskService, never()).markResultHandlingFailed(anyString(), anyString());
    }
}

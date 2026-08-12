package com.smartview.task.mq;

import com.smartview.common.exception.BusinessException;
import com.smartview.report.service.ReportTaskService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * 报告结果消费者测试：业务异常进入 DLQ 前先补偿收口，系统异常重试。
 */
@ExtendWith(MockitoExtension.class)
class ReportResultConsumerTest {

    @Mock
    private ReportTaskService reportTaskService;

    @Test
    void businessException_rejectsToDlqAndCompensates() {
        doThrow(new BusinessException("报告生成结果契约校验失败"))
                .when(reportTaskService).handleResult(any());

        ReportResultConsumer consumer = new ReportResultConsumer(reportTaskService);
        ReportGenerateResultMessage message = ReportGenerateResultMessage.builder()
                .taskId("t1").build();

        assertThatThrownBy(() -> consumer.handleReportGenerateResult(message))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verify(reportTaskService).markResultHandlingFailed(eq("t1"), any());
    }

    @Test
    void systemException_throwsRuntimeForRetry() {
        doThrow(new IllegalStateException("数据库连接失败"))
                .when(reportTaskService).handleResult(any());

        ReportResultConsumer consumer = new ReportResultConsumer(reportTaskService);
        ReportGenerateResultMessage message = ReportGenerateResultMessage.builder()
                .taskId("t1").build();

        assertThatThrownBy(() -> consumer.handleReportGenerateResult(message))
                .isInstanceOf(RuntimeException.class);
    }
}

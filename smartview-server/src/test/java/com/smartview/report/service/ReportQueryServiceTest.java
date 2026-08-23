package com.smartview.report.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartview.generated.web.model.InterviewReport;
import com.smartview.interview.dto.AnswerHistoryAssembler;
import com.smartview.interview.entity.InterviewSession;
import com.smartview.interview.enums.InterviewSessionStatus;
import com.smartview.interview.mapper.InterviewSessionMapper;
import com.smartview.report.entity.ReferenceAnswer;
import com.smartview.report.enums.ReferenceAnswerType;
import com.smartview.report.mapper.InterviewReportMapper;
import com.smartview.report.mapper.ReferenceAnswerMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportQueryServiceTest {

    @Mock private InterviewReportMapper reportMapper;
    @Mock private ReferenceAnswerMapper referenceAnswerMapper;
    @Mock private InterviewSessionMapper sessionMapper;
    @Mock private AnswerHistoryAssembler answerHistoryAssembler;
    @Mock private ReportTaskService reportTaskService;

    private ReportQueryService service;

    /**
     * 初始化 Lambda 包装器涉及的实体表元数据（MyBatis-Plus 缓存）。
     *
     * 查询服务内部会构造 LambdaQueryWrapper（findReportBySession 用报告实体、
     * loadReferenceAnswerDtos 用参考答案实体），其列名解析依赖 TableInfoHelper 缓存；
     * 沿用 ReportTaskServiceTest 既有约定手动初始化，避免 "can not find lambda cache"。
     */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                com.smartview.report.entity.InterviewReport.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                ReferenceAnswer.class);
    }

    @BeforeEach
    void setUp() {
        service = new ReportQueryService(reportMapper, referenceAnswerMapper, sessionMapper,
                answerHistoryAssembler, reportTaskService, new ObjectMapper());
    }

    private com.smartview.report.entity.InterviewReport reportEntity() {
        return com.smartview.report.entity.InterviewReport.builder()
                .id(88L).sessionId(66L).userId(7L).resumeProfileId(12L)
                .overallScore(76).readinessLevel("READY").roleFitScore(82)
                .summary("整体表现良好")
                .strengthsJson("[\"基础知识扎实\"]")
                .coverageJson("{\"basicCoverage\":0.5,\"projectCoverage\":0.0,\"scenarioCoverage\":0.0}")
                .status("SUCCESS").build();
    }

    private InterviewSession sessionEntity() {
        return InterviewSession.builder().id(66L).userId(7L).resumeProfileId(12L)
                .roleDirection("JAVA_BACKEND").status(InterviewSessionStatus.COMPLETED.getCode()).build();
    }

    @Test
    void getReportBySession_组装完整报告() {
        when(sessionMapper.selectById(66L)).thenReturn(sessionEntity());
        when(reportMapper.selectOne(any())).thenReturn(reportEntity());
        when(answerHistoryAssembler.load(66L)).thenReturn(List.of());
        when(referenceAnswerMapper.selectList(any())).thenReturn(List.of(
                ReferenceAnswer.builder().id(1L).reportId(88L).questionId(11L)
                        .answerType(ReferenceAnswerType.BASIC_KEY_POINTS.getCode())
                        .referenceContent("volatile 保证可见性").keyPointsJson("[\"happens-before\"]")
                        .build()));

        InterviewReport dto = service.getReportBySession(7L, 66L);

        assertThat(dto.getId()).isEqualTo("88");
        assertThat(dto.getOverallScore()).isEqualTo(76);
        assertThat(dto.getReadinessLevel())
                .isEqualTo(InterviewReport.ReadinessLevelEnum.READY);
        assertThat(dto.getRoleFitScore()).isEqualTo(82);
        assertThat(dto.getSummary()).isEqualTo("整体表现良好");
        assertThat(dto.getStrengths()).containsExactly("基础知识扎实");
        assertThat(dto.getCoverage().getBasicCoverage()).isEqualTo(0.5);
        assertThat(dto.getRoleDirection())
                .isEqualTo(InterviewReport.RoleDirectionEnum.JAVA_BACKEND);
        assertThat(dto.getAnswers()).isEmpty();
        assertThat(dto.getReferenceAnswers()).hasSize(1);
        // 参考答案 answerType 是生成 DTO 的内嵌枚举（实体无同名内嵌枚举），
        // 按简报说明以全限定名引用，避免与实体 ReferenceAnswer 同名冲突。
        assertThat(dto.getReferenceAnswers().get(0).getAnswerType())
                .isEqualTo(com.smartview.generated.web.model.ReferenceAnswer.AnswerTypeEnum.BASIC_KEY_POINTS);
    }

    @Test
    void getReportBySession_会话不存在抛404() {
        when(sessionMapper.selectById(999L)).thenReturn(null);
        assertThatThrownBy(() -> service.getReportBySession(7L, 999L))
                .hasMessageContaining("会话不存在");
    }

    @Test
    void getReportBySession_归属不符抛403() {
        when(sessionMapper.selectById(66L)).thenReturn(sessionEntity());
        assertThatThrownBy(() -> service.getReportBySession(99L, 66L))
                .hasMessageContaining("无权访问该面试会话");
    }

    @Test
    void getReport_报告不存在抛404() {
        when(reportMapper.selectById(999L)).thenReturn(null);
        assertThatThrownBy(() -> service.getReport(7L, 999L))
                .hasMessageContaining("面试报告不存在");
    }

    @Test
    void getReport_归属不符抛403() {
        when(reportMapper.selectById(88L)).thenReturn(reportEntity());
        assertThatThrownBy(() -> service.getReport(99L, 88L))
                .hasMessageContaining("无权访问该面试报告");
    }

    @Test
    void listReports_分页返回当前用户报告摘要() {
        Page<com.smartview.report.entity.InterviewReport> page = new Page<>(1, 10);
        page.setRecords(List.of(reportEntity()));
        page.setTotal(1);
        when(reportMapper.selectPage(any(Page.class), any())).thenReturn(page);
        when(sessionMapper.selectBatchIds(List.of(66L))).thenReturn(List.of(sessionEntity()));

        com.smartview.generated.web.model.InterviewReportPage result = service.listReports(7L, 1, 10);

        // 授权边界关键断言：分页查询必须按 userId 精确过滤（且按创建时间倒序）。
        // 若未来误删 .eq(userId) 会导致接口返回全部用户数据，此处必须守住。
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<com.smartview.report.entity.InterviewReport>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(reportMapper).selectPage(any(Page.class), captor.capture());
        LambdaQueryWrapper<com.smartview.report.entity.InterviewReport> wrapper = captor.getValue();
        // SQL 段使用 MyBatis-Plus 命名占位符（#{ew.paramNameValuePairs.xxx}），
        // 此处断言列名与排序方向即可；参数值由下方 paramNameValuePairs 精确校验
        assertThat(wrapper.getSqlSegment())
                .contains("user_id")
                .contains("ORDER BY created_at DESC");
        // 过滤参数值必须是当前用户 ID（而非其他用户/空值）
        assertThat(wrapper.getParamNameValuePairs().values()).contains(7L);

        assertThat(result.getItems()).hasSize(1);
        com.smartview.generated.web.model.InterviewReportSummary item = result.getItems().get(0);
        assertThat(item.getId()).isEqualTo("88");
        assertThat(item.getSessionId()).isEqualTo("66");
        // 摘要模型使用独立枚举类，需按摘要枚举断言
        assertThat(item.getRoleDirection()).isEqualTo(
                com.smartview.generated.web.model.InterviewReportSummary.RoleDirectionEnum.JAVA_BACKEND);
        assertThat(item.getStatus()).isEqualTo(
                com.smartview.generated.web.model.InterviewReportSummary.StatusEnum.SUCCESS);
        assertThat(item.getOverallScore()).isEqualTo(76);
        assertThat(item.getReadinessLevel()).isEqualTo(
                com.smartview.generated.web.model.InterviewReportSummary.ReadinessLevelEnum.READY);
        assertThat(item.getRoleFitScore()).isEqualTo(82);
        assertThat(item.getSummary()).isEqualTo("整体表现良好");
        assertThat(result.getPage()).isEqualTo(1);
        assertThat(result.getSize()).isEqualTo(10);
        assertThat(result.getTotal()).isEqualTo(1L);
    }

    @Test
    void listReports_会话缺失时方向字段缺省不阻断列表() {
        Page<com.smartview.report.entity.InterviewReport> page = new Page<>(1, 10);
        page.setRecords(List.of(reportEntity()));
        page.setTotal(1);
        when(reportMapper.selectPage(any(), any())).thenReturn(page);
        // 会话数据异常缺失：selectBatchIds 返回空，方向字段应为 null 而非抛错
        when(sessionMapper.selectBatchIds(List.of(66L))).thenReturn(List.of());

        com.smartview.generated.web.model.InterviewReportPage result = service.listReports(7L, 1, 10);

        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().get(0).getRoleDirection()).isNull();
    }

    @Test
    void retryReport_委托重新生成并返回现状() {
        when(reportMapper.selectById(88L)).thenReturn(reportEntity());
        when(sessionMapper.selectById(66L)).thenReturn(sessionEntity());
        when(answerHistoryAssembler.load(66L)).thenReturn(List.of());
        when(referenceAnswerMapper.selectList(any())).thenReturn(List.of());

        InterviewReport dto = service.retryReport(7L, 88L);

        verify(reportTaskService).retryReportGeneration(reportEntity());
        assertThat(dto.getId()).isEqualTo("88");
    }
}

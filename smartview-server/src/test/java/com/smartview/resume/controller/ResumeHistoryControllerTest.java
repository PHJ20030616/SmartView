package com.smartview.resume.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartview.common.api.ApiResponse;
import com.smartview.generated.web.model.ResumeFile;
import com.smartview.generated.web.model.ResumeFilePage;
import com.smartview.resume.dto.ResumeFileDtoMapper;
import com.smartview.resume.service.ResumeFileService;
import com.smartview.security.SecurityContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 简历历史控制器单元测试（Task 7.1）。
 *
 * 验证 GET /api/resumes 路径：取当前登录用户、按分页委托查询并统一包装；
 * 分页参数越界时收敛到合法范围，避免非法入参导致全表扫描。
 */
@ExtendWith(MockitoExtension.class)
class ResumeHistoryControllerTest {

    @Mock
    private ResumeFileService resumeFileService;
    @Mock
    private ResumeFileDtoMapper resumeFileDtoMapper;

    private ResumeHistoryController controller;

    @BeforeEach
    void setUp() {
        // @Mock 注入发生在测试实例创建之后，控制器必须在注入完成后构造，
        // 否则构造时拿到 null 服务引用。
        controller = new ResumeHistoryController(resumeFileService, resumeFileDtoMapper);
    }

    @Test
    void listResumeHistory_取当前用户并按分页委托查询() {
        // 实体与契约 DTO 类名相同（ResumeFile），实体用全限定名避免命名冲突
        com.smartview.resume.entity.ResumeFile entity = com.smartview.resume.entity.ResumeFile.builder()
                .id(88L).userId(7L).originalFilename("张三_Java开发.pdf").parseStatus("SUCCESS").build();
        Page<com.smartview.resume.entity.ResumeFile> pageResult = new Page<>(1, 10);
        pageResult.setRecords(List.of(entity));
        pageResult.setTotal(1);
        ResumeFile dto = new ResumeFile("88", "7", "张三_Java开发.pdf", ResumeFile.ParseStatusEnum.SUCCESS);
        when(resumeFileService.pageUserResumeFiles(7L, 1, 10)).thenReturn(pageResult);
        when(resumeFileDtoMapper.toDtos(List.of(entity), 7L)).thenReturn(List.of(dto));

        try (MockedStatic<SecurityContextHolder> security = mockStatic(SecurityContextHolder.class)) {
            security.when(SecurityContextHolder::getCurrentUserId).thenReturn(7L);

            ApiResponse<ResumeFilePage> response = controller.listResumeHistory(1, 10);

            // 用户 ID 只来自安全上下文，前端无法指定他人数据
            verify(resumeFileService).pageUserResumeFiles(7L, 1, 10);
            assertThat(response.getData().getItems()).hasSize(1);
            assertThat(response.getData().getItems().get(0).getOriginalFilename())
                    .isEqualTo("张三_Java开发.pdf");
            assertThat(response.getData().getPage()).isEqualTo(1);
            assertThat(response.getData().getTotal()).isEqualTo(1);
        }
    }

    @Test
    void listResumeHistory_分页参数越界时收敛为合法范围() {
        // 页码 0 → 1；每页 999 → 上限 50（与契约 maximum=50 一致）
        when(resumeFileService.pageUserResumeFiles(7L, 1, 50)).thenReturn(new Page<>(1, 50));

        try (MockedStatic<SecurityContextHolder> security = mockStatic(SecurityContextHolder.class)) {
            security.when(SecurityContextHolder::getCurrentUserId).thenReturn(7L);

            controller.listResumeHistory(0, 999);

            verify(resumeFileService).pageUserResumeFiles(7L, 1, 50);
        }
    }
}

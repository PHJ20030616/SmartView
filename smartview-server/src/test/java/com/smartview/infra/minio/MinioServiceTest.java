package com.smartview.infra.minio;

import com.smartview.common.exception.BusinessException;
import com.smartview.config.properties.MinioProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.Http.Method;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MinioServiceTest {

    @Mock
    private MinioClient minioClient;

    private MinioProperties minioProperties;

    @BeforeEach
    void setUp() throws Exception {
        minioProperties = new MinioProperties();
        minioProperties.setBucket("smartview");
        // 构造 MinioService 时会校验 Bucket，测试需要显式模拟已存在的存储桶。
        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
    }

    @Test
    void generatePresignedUrl_shouldSetGetMethodForMinioV9() throws Exception {
        when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .thenReturn("https://minio.example/resume.pdf");

        MinioService minioService = new MinioService(minioClient, minioProperties);

        String url = minioService.generatePresignedUrl("resumes/8/resume.pdf", 1);

        assertThat(url).isEqualTo("https://minio.example/resume.pdf");
        ArgumentCaptor<GetPresignedObjectUrlArgs> argsCaptor =
                ArgumentCaptor.forClass(GetPresignedObjectUrlArgs.class);
        verify(minioClient).getPresignedObjectUrl(argsCaptor.capture());
        assertThat(argsCaptor.getValue().method()).isEqualTo(Method.GET);
    }

    @Test
    void generatePresignedUrl_shouldWrapMinioException() throws Exception {
        when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .thenThrow(new RuntimeException("MinIO 服务不可用"));

        MinioService minioService = new MinioService(minioClient, minioProperties);

        // 验证底层 MinIO 异常不会泄漏到业务层，调用方只接收稳定的中文业务提示。
        assertThatThrownBy(() -> minioService.generatePresignedUrl("resumes/8/resume.pdf", 1))
                .isInstanceOf(BusinessException.class)
                .hasMessage("生成文件访问链接失败");
    }
}

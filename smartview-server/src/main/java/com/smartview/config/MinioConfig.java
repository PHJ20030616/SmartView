package com.smartview.config;

import com.smartview.config.properties.MinioProperties;
import io.minio.MinioClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO 对象存储配置类
 * <p>
 * 配置 MinIO 客户端，用于文件上传、下载和管理。
 * MinIO 是一个高性能的对象存储服务，兼容 Amazon S3 API。
 * </p>
 */
@Configuration
public class MinioConfig {

    /**
     * 创建 MinIO 客户端 Bean
     * <p>
     * 根据配置属性创建 MinioClient 实例，用于与 MinIO 服务器交互。
     * </p>
     *
     * @param properties MinIO 配置属性
     * @return MinioClient 实例
     */
    @Bean
    public MinioClient minioClient(MinioProperties properties) {
        return MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
    }
}

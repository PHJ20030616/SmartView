package com.smartview.infra.minio;

import com.smartview.common.exception.BusinessException;
import com.smartview.config.properties.MinioProperties;
import io.minio.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * MinIO 对象存储服务
 *
 * 功能说明：
 * - 封装 MinIO 客户端操作，提供文件上传、下载、删除、预签名 URL 生成等功能
 * - 自动处理 Bucket 不存在的情况（首次上传时自动创建）
 * - 统一异常处理，将 MinIO 原生异常转换为业务异常
 * - 支持文件路径规划（按业务模块和用户 ID 组织目录结构）
 *
 * 技术要点：
 * - 使用 MinIO Java SDK 8.5.x
 * - 文件路径格式：{module}/{userId}/{uuid}.{extension}
 * - 预签名 URL 有效期默认 1 小时，避免链接泄露风险
 * - 删除操作幂等，文件不存在时不抛异常
 *
 * 依赖项：
 * - MinioClient：MinIO 客户端实例（由 MinioConfig 注入）
 * - MinioProperties：MinIO 配置属性（endpoint、bucket 等）
 *
 * @author SmartView Team
 * @since 2026-07-23
 */
@Slf4j
@Service
public class MinioService {

    private final MinioClient minioClient;
    private final MinioProperties minioProperties;

    /**
     * 构造函数注入依赖
     *
     * @param minioClient     MinIO 客户端
     * @param minioProperties MinIO 配置属性
     */
    public MinioService(MinioClient minioClient, MinioProperties minioProperties) {
        this.minioClient = minioClient;
        this.minioProperties = minioProperties;
        ensureBucketExists();
    }

    /**
     * 确保 Bucket 存在，不存在则创建
     * 在服务初始化时调用，避免每次上传都检查
     */
    private void ensureBucketExists() {
        try {
            String bucket = minioProperties.getBucket();
            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(bucket).build()
            );
            if (!exists) {
                minioClient.makeBucket(
                        MakeBucketArgs.builder().bucket(bucket).build()
                );
                log.info("MinIO bucket 创建成功: {}", bucket);
            }
        } catch (Exception e) {
            log.error("MinIO bucket 初始化失败", e);
            throw new BusinessException("对象存储服务初始化失败");
        }
    }

    /**
     * 上传简历文件到 MinIO
     *
     * @param file   上传的文件
     * @param userId 用户 ID，用于组织文件目录结构
     * @return MinIO 中的对象 Key（格式：resumes/{userId}/{uuid}.pdf）
     * @throws BusinessException 上传失败时抛出
     */
    public String uploadResumeFile(MultipartFile file, Long userId) {
        try {
            // 生成唯一的对象 Key
            String objectKey = generateResumeObjectKey(userId, file.getOriginalFilename());

            // 上传文件到 MinIO
            try (InputStream inputStream = file.getInputStream()) {
                minioClient.putObject(
                        PutObjectArgs.builder()
                                .bucket(minioProperties.getBucket())
                                .object(objectKey)
                                .stream(inputStream, file.getSize(), -1L)
                                .contentType(file.getContentType())
                                .build()
                );
            }

            log.info("文件上传成功，objectKey={}, userId={}, size={}", objectKey, userId, file.getSize());
            return objectKey;

        } catch (Exception e) {
            log.error("文件上传失败，userId={}, filename={}", userId, file.getOriginalFilename(), e);
            throw new BusinessException("文件上传失败：" + e.getMessage());
        }
    }

    /**
     * 生成简历文件的对象 Key
     * 格式：resumes/{userId}/{uuid}.{extension}
     *
     * @param userId           用户 ID
     * @param originalFilename 原始文件名
     * @return 对象 Key
     */
    private String generateResumeObjectKey(Long userId, String originalFilename) {
        String uuid = UUID.randomUUID().toString();
        String extension = extractFileExtension(originalFilename);
        return String.format("resumes/%d/%s.%s", userId, uuid, extension);
    }

    /**
     * 提取文件扩展名
     *
     * @param filename 文件名
     * @return 扩展名（小写，不含点）
     */
    private String extractFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "pdf";
        }
        int lastDotIndex = filename.lastIndexOf('.');
        return filename.substring(lastDotIndex + 1).toLowerCase();
    }

    /**
     * 生成预签名下载 URL
     * 用于 AI 服务从 MinIO 下载文件进行解析
     *
     * @param objectKey 对象 Key
     * @param expiryHours 有效期（小时），默认 1 小时
     * @return 预签名 URL
     * @throws BusinessException 生成失败时抛出
     */
    public String generatePresignedUrl(String objectKey, int expiryHours) {
        try {
            GetPresignedObjectUrlArgs args = GetPresignedObjectUrlArgs.builder()
                    .bucket(minioProperties.getBucket())
                    .object(objectKey)
                    .expiry(expiryHours, TimeUnit.HOURS)
                    .build();
            return minioClient.getPresignedObjectUrl(args);
        } catch (Exception e) {
            log.error("生成预签名 URL 失败，objectKey={}", objectKey, e);
            throw new BusinessException("生成文件访问链接失败");
        }
    }

    /**
     * 删除文件
     * 幂等操作：文件不存在时不抛异常
     *
     * @param objectKey 对象 Key
     */
    public void deleteFile(String objectKey) {
        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(minioProperties.getBucket())
                            .object(objectKey)
                            .build()
            );
            log.info("文件删除成功，objectKey={}", objectKey);
        } catch (Exception e) {
            // 删除失败仅记录日志，不抛异常（允许孤儿文件存在，后续通过清理任务处理）
            log.warn("文件删除失败，objectKey={}, error={}", objectKey, e.getMessage());
        }
    }

    /**
     * 检查文件是否存在
     *
     * @param objectKey 对象 Key
     * @return true=存在，false=不存在
     */
    public boolean fileExists(String objectKey) {
        try {
            minioClient.statObject(
                    StatObjectArgs.builder()
                            .bucket(minioProperties.getBucket())
                            .object(objectKey)
                            .build()
            );
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}

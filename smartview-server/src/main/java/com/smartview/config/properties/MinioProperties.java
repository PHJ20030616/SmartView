package com.smartview.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MinIO 对象存储配置属性类
 * <p>
 * 绑定配置文件中 smartview.minio 前缀的属性，用于连接 MinIO 对象存储服务。
 * </p>
 */
@ConfigurationProperties(prefix = "smartview.minio")
public class MinioProperties {

    /** MinIO 服务端点 URL */
    private String endpoint;

    /** MinIO 访问密钥 */
    private String accessKey;

    /** MinIO 私密密钥 */
    private String secretKey;

    /** 默认存储桶名称 */
    private String bucket;

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }
}

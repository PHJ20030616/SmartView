package com.smartview.common.validation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * JSON Schema 校验器
 *
 * 功能说明：
 * - 加载 contracts/mq/ 目录下的 JSON Schema 文件
 * - 提供校验方法，验证消息是否符合契约定义
 * - 支持 Draft-07 规范（与消息队列契约的 $schema 一致）
 *
 * 可靠性保障：
 * - 启动时 fail-fast：Schema 加载失败直接阻止应用启动，避免运行时静默跳过校验
 * - 序列化时排除 null 字段：契约允许可选字段"缺席"，但不允许 JSON null 值
 *
 * @author SmartView Team
 * @since 2026-07-25
 */
@Slf4j
@Component
public class SchemaValidator {

    private final ObjectMapper objectMapper;
    /**
     * 专门用于 Schema 校验的 ObjectMapper，序列化时排除 null 值字段
     * 避免 DTO 中的 null 字段被序列化为 JSON null 从而违反 Schema 约束
     */
    private ObjectMapper schemaValidationMapper;
    /**
     * 简历解析结果 Schema，启动时强制加载，null 表示应用启动未完成
     */
    private JsonSchema resumeParseResultSchema;
    /**
     * 简历向量入库结果 Schema，启动时强制加载。
     *
     * 向量结果会直接驱动 ai_task 的终态更新，必须先通过契约校验，
     * 避免格式错误或旧版本字段误写任务状态。
     */
    private JsonSchema resumeVectorizeResultSchema;

    public SchemaValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 初始化时预加载所有 Schema
     * Schema 加载失败直接抛异常阻止应用启动，确保运行时校验始终可用
     *
     * @throws IllegalStateException Schema 不存在或格式错误时抛出
     */
    @PostConstruct
    public void init() {
        // 创建独立 ObjectMapper，序列化时排除 null 值字段
        schemaValidationMapper = objectMapper.copy();
        schemaValidationMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);

        resumeParseResultSchema = loadSchema("/contracts/mq/resume_parse_result.schema.json");
        if (resumeParseResultSchema == null) {
            throw new IllegalStateException(
                    "无法加载 resume_parse_result.schema.json，"
                            + "请确认该文件已正确打包到 classpath:contracts/mq/ 目录下");
        }
        resumeVectorizeResultSchema = loadSchema("/contracts/mq/resume_vectorize_result.schema.json");
        if (resumeVectorizeResultSchema == null) {
            throw new IllegalStateException(
                    "无法加载 resume_vectorize_result.schema.json，"
                            + "请确认该文件已正确打包到 classpath:contracts/mq/ 目录下");
        }
        log.info("resume_parse_result 和 resume_vectorize_result Schema 加载成功");
    }

    /**
     * 校验简历解析结果消息是否符合契约定义
     * Schema 在启动时已验证加载成功，此处不再做空检查
     *
     * @param message 待校验的消息对象
     * @throws IllegalArgumentException 校验失败时抛出，包含所有违反约束的描述
     */
    public void validateResumeParseResult(Object message) {
        // 使用 NON_NULL 序列化器，避免可选字段的 null 值触发 Schema 类型错误
        validate(message, resumeParseResultSchema);
    }

    /**
     * 校验简历向量入库结果消息是否符合契约定义。
     *
     * @param message 待校验的消息对象
     * @throws IllegalArgumentException 校验失败时抛出
     */
    public void validateResumeVectorizeResult(Object message) {
        validate(message, resumeVectorizeResultSchema);
    }

    /**
     * 统一执行 Schema 校验，确保两个 MQ 结果消费者使用一致的错误处理逻辑。
     */
    private void validate(Object message, JsonSchema schema) {
        // 使用 NON_NULL 序列化器，避免可选字段的 null 值触发 Schema 类型错误
        JsonNode jsonNode = schemaValidationMapper.valueToTree(message);
        Set<ValidationMessage> errors = schema.validate(jsonNode);
        if (!errors.isEmpty()) {
            String errorDetails = errors.stream()
                    .map(ValidationMessage::getMessage)
                    .collect(Collectors.joining("; "));
            log.warn("JSON Schema 校验失败：{}", errorDetails);
            throw new IllegalArgumentException("消息格式校验失败：" + errorDetails);
        }
    }

    /**
     * 从 classpath 加载 JSON Schema 文件
     *
     * @param path classpath 路径，例如 /contracts/mq/resume_parse_result.schema.json
     * @return 解析后的 JsonSchema，失败返回 null（由调用方处理）
     */
    private JsonSchema loadSchema(String path) {
        try (InputStream is = getClass().getResourceAsStream(path)) {
            if (is == null) {
                log.error("Schema 文件未在 classpath 中找到：{}", path);
                return null;
            }
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
            return factory.getSchema(is);
        } catch (IOException e) {
            log.error("Schema 文件读取失败：{}", path, e);
            return null;
        }
    }
}

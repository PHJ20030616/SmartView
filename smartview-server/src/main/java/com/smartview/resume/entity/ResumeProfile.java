package com.smartview.resume.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 简历画像实体类
 *
 * 功能说明：
 * - 映射数据库 resume_profile 表
 * - 存储 AI 解析后的结构化简历数据（姓名、联系方式、教育经历、工作经验等）
 * - 支持多版本管理（同一份简历文件可重新解析生成新版本画像）
 * - 支持用户确认机制（用户可编辑AI解析结果后确认）
 * - 支持软删除（deleted 字段配合 @TableLogic）
 * - 支持字段自动填充（createdAt 和 updatedAt 配合 MyMetaObjectHandler）
 *
 * 技术要点：
 * - @TableName("resume_profile")：映射到 resume_profile 表
 * - @TableId(type = IdType.AUTO)：主键自增策略
 * - @TableLogic：标记 deleted 字段为逻辑删除字段（0=未删除，1=已删除）
 * - @TableField(fill = FieldFill.INSERT)：插入时自动填充 createdAt
 * - @TableField(fill = FieldFill.INSERT_UPDATE)：插入和更新时自动填充 updatedAt
 *
 * 业务流程：
 * 1. AI 解析完成 → 创建 ResumeProfile 记录（confirm_status=UNCONFIRMED，version=1）
 * 2. 用户查看并编辑画像 → 更新各 JSON 字段
 * 3. 用户确认画像 → 更新 confirm_status=CONFIRMED，记录 confirmed_at
 * 4. 用户重新解析 → 创建新版本画像（version=2），旧版本保留但不再展示
 *
 * JSON 字段设计原则：
 * - 使用 MySQL JSON 类型存储复杂结构，支持 JSON 函数查询
 * - 同时保留关键字段（如 candidate_name）为独立列，便于索引和搜索
 * - raw_text 保留原文，支持审计和二次解析
 *
 * @author SmartView Team
 * @since 2026-07-23
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("resume_profile")
public class ResumeProfile {

    /**
     * 简历画像ID，主键，数据库自增
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 所属用户ID
     * 外键关联 user 表，用于权限控制和用户维度查询
     * 冗余字段（可通过 resume_file_id → resume_file.user_id 获取），
     * 但冗余设计能简化查询，避免多表 JOIN
     */
    private Long userId;

    /**
     * 对应的简历文件ID
     * 外键关联 resume_file 表，建立画像与原始文件的关联
     * 一个简历文件可以对应多个版本的画像（version 字段区分）
     */
    private Long resumeFileId;

    /**
     * 候选人姓名
     * 从简历中提取的候选人姓名，独立列便于：
     * 1. 按姓名搜索简历
     * 2. 姓名模糊匹配
     * 3. 前端列表展示
     */
    private String candidateName;

    /**
     * 联系方式 JSON
     * 存储手机号、邮箱、微信等联系方式
     * 示例：
     * {
     *   "phone": "13800138000",
     *   "email": "zhangsan@example.com",
     *   "wechat": "zhangsan_wx",
     *   "linkedin": "https://linkedin.com/in/zhangsan"
     * }
     */
    @TableField("contact_info_json")
    private String contactInfoJson;

    /**
     * 教育经历 JSON
     * 存储学校、专业、学历、时间等信息，支持多条记录
     * 示例：
     * [
     *   {
     *     "school": "清华大学",
     *     "major": "计算机科学与技术",
     *     "degree": "本科",
     *     "startDate": "2015-09",
     *     "endDate": "2019-06",
     *     "gpa": "3.8/4.0"
     *   }
     * ]
     */
    @TableField("education_json")
    private String educationJson;

    /**
     * 工作经历 JSON
     * 存储公司、职位、职责、时间等信息，支持多条记录
     * 示例：
     * [
     *   {
     *     "company": "字节跳动",
     *     "position": "Java开发工程师",
     *     "responsibilities": ["负责推荐系统后端开发", "优化接口性能"],
     *     "startDate": "2019-07",
     *     "endDate": "2022-03",
     *     "achievements": ["将接口响应时间从500ms优化到50ms"]
     *   }
     * ]
     */
    @TableField("work_experience_json")
    private String workExperienceJson;

    /**
     * 项目经历 JSON
     * 存储项目名称、职责、技术栈、成果等信息，支持多条记录
     * 示例：
     * [
     *   {
     *     "projectName": "电商平台",
     *     "role": "后端负责人",
     *     "description": "负责订单系统和支付系统的设计与开发",
     *     "techStack": ["Spring Boot", "MySQL", "Redis", "RocketMQ"],
     *     "startDate": "2020-01",
     *     "endDate": "2021-06",
     *     "achievements": ["支持日均10万订单", "支付成功率99.9%"]
     *   }
     * ]
     */
    @TableField("project_experience_json")
    private String projectExperienceJson;

    /**
     * 技能栈 JSON
     * 存储编程语言、框架、工具、软技能等，支持分类存储
     * 示例：
     * {
     *   "languages": ["Java", "Python", "JavaScript"],
     *   "frameworks": ["Spring Boot", "Django", "React"],
     *   "databases": ["MySQL", "Redis", "MongoDB"],
     *   "tools": ["Git", "Docker", "Kubernetes"],
     *   "softSkills": ["团队协作", "问题解决", "技术文档编写"]
     * }
     */
    @TableField("skills_json")
    private String skillsJson;

    /**
     * 简历原文
     * PDF 提取或 OCR 得到的简历纯文本内容
     * 用途：
     * 1. 审计追踪：记录 AI 解析的原始输入
     * 2. 二次解析：当 AI 模型升级时，可基于原文重新解析
     * 3. 关键词搜索：支持全文检索
     * 4. 人工审核：当 AI 解析结果不准确时，人工对照原文修正
     */
    @TableField("raw_text")
    private String rawText;

    /**
     * 完整画像 JSON
     * 包含上述所有字段的汇总，以及 AI 可能提取的其他信息
     * 用途：
     * 1. 一次性获取完整简历数据（减少字段访问）
     * 2. 存储 AI 生成的额外信息（如能力评估、匹配度分析等）
     * 3. 向前端提供标准化的 JSON 响应
     *
     * 示例结构（与上述各 JSON 字段保持一致）：
     * {
     *   "candidateName": "张三",
     *   "contactInfo": {...},
     *   "education": [...],
     *   "workExperience": [...],
     *   "projectExperience": [...],
     *   "skills": {...},
     *   "summary": "5年Java开发经验，熟悉Spring生态..."
     * }
     */
    @TableField("profile_json")
    private String profileJson;

    /**
     * 用户确认状态
     * 枚举值：
     * - UNCONFIRMED：未确认（AI刚生成，用户尚未查看或编辑）
     * - CONFIRMED：已确认（用户查看并确认，或编辑后确认）
     *
     * 业务规则：
     * - 只有已确认的画像才能用于后续的职位匹配、简历推荐等业务
     * - 未确认的画像可能包含 AI 解析错误，需要用户人工校验
     *
     * 注意：数据库存储字符串，业务代码应使用 ConfirmStatus 枚举
     */
    @TableField("confirm_status")
    private String confirmStatus;

    /**
     * 用户确认时间
     * 记录用户点击"确认"按钮的时间
     * 用于统计用户从上传到确认的耗时，优化产品体验
     */
    private LocalDateTime confirmedAt;

    /**
     * 画像版本号
     * 用户重新上传或重新解析时递增
     * 同一个 resume_file_id 可能有多个版本的画像：
     * - version=1：首次解析
     * - version=2：用户重新解析（如 AI 模型升级）
     * - version=3：再次重新解析
     *
     * 查询时通常只取最新版本（MAX(version)）
     */
    private Integer version;

    /**
     * 记录创建时间
     * 由 MyMetaObjectHandler 在插入时自动填充，业务代码无需手动设置
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 记录最后更新时间
     * 由 MyMetaObjectHandler 在插入和更新时自动填充，业务代码无需手动设置
     * 用户编辑画像时会自动更新此字段
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /**
     * 软删除标记：0=未删除，1=已删除
     * 配合 @TableLogic 注解实现逻辑删除：
     * - 查询时自动添加 deleted=0 条件
     * - 调用 deleteById 时执行 UPDATE 而非 DELETE
     * - 旧版本画像可以软删除，只保留最新版本
     */
    @TableLogic
    private Integer deleted;
}

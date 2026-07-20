# SmartView 简历处理工作流

> 本文档明确简历上传、解析、确认、向量入库、方向选择和画像分析的完整流程与数据一致性保障。

## 1. 整体流程

```mermaid
sequenceDiagram
    autonumber
    actor U as 用户
    participant F as 前端
    participant S as Spring Boot
    participant M as MySQL
    participant O as MinIO
    participant Q as RabbitMQ
    participant A as FastAPI
    participant C as Chroma

    U->>F: 上传 PDF 简历
    F->>S: POST /api/resumes
    S->>M: 创建 resume_file (parse_status=PENDING)
    S->>O: 保存 PDF 文件
    S->>M: 创建 ai_task (type=RESUME_PARSE, status=PENDING)
    S->>Q: 投递 resume_parse_task
    S-->>F: 返回 resumeFileId

    A->>Q: 消费解析任务
    A->>M: 查询 ai_task.task_status，如果已 SUCCESS 则跳过
    A->>O: 读取 PDF
    A->>A: 文本提取 + OCR 兜底
    A->>A: LLM 结构化解析
    A->>Q: 投递 resume_parse_result
    A->>M: 更新 ai_task.task_status=SUCCESS

    S->>Q: 消费解析结果
    S->>M: 查询 ai_task.task_status，如果已 SUCCESS 检查是否已落库
    S->>M: 写入 resume_profile (confirm_status=UNCONFIRMED, version=1)
    S->>M: 更新 resume_file.parse_status=SUCCESS

    U->>F: 查看解析结果
    F->>S: GET /api/resume-profiles/{profileId}
    S-->>F: 返回结构化简历
    U->>F: 确认或编辑简历
    F->>S: PUT /api/resume-profiles/{profileId}
    F->>S: POST /api/resume-profiles/{profileId}/confirm
    S->>M: 更新 confirm_status=CONFIRMED

    Note over S,A: 简历确认后，同步或短链路异步完成向量入库
    S->>M: 创建 ai_task (type=RESUME_VECTORIZE, status=PENDING)
    S->>Q: 投递 resume_vectorize_task
    A->>Q: 消费向量化任务
    A->>M: 查询 ai_task.task_status
    A->>A: 切片简历项目、技能、经历
    A->>C: 写入 resume_profile_chunks（带 user_id, resume_profile_id, profile_version）
    A->>Q: 投递 resume_vectorize_result
    S->>Q: 消费向量化结果
    S->>M: 更新 ai_task.task_status=SUCCESS

    Note over F,S: 向量入库成功后才允许选择面试方向
    U->>F: 选择面试方向（Java 后端 / Agent 开发）
    F->>S: POST /api/profile-analyses
    S->>M: 检查该方向 profile_analysis 是否存在
    alt 已存在且 SUCCESS
        S-->>F: 返回已有分析
    else 不存在或 FAILED
        S->>M: 创建 ai_task (type=PROFILE_ANALYZE, status=PENDING)
        S->>Q: 投递 profile_analyze_task
        A->>Q: 消费画像分析任务
        A->>M: 查询 ai_task.task_status
        A->>C: 检索简历切片（过滤 user_id, resume_profile_id, profile_version）
        A->>C: 检索知识库和面经（过滤 role_direction）
        A->>A: 生成技能标签、项目图谱、风险点、建议主题、阶段目标
        A->>Q: 投递 profile_analyze_result
        S->>Q: 消费画像分析结果
        S->>M: 写入 profile_analysis
        S->>M: 更新 ai_task.task_status=SUCCESS
        S-->>F: 返回分析状态
    end

    Note over F,S: 画像分析成功后才允许开始面试
    U->>F: 点击"开始面试"
    F->>S: POST /api/interview-sessions
    S->>M: 校验 resume_profile.confirm_status=CONFIRMED
    S->>M: 校验 profile_analysis 存在且成功
    S->>M: 创建 interview_session
    S->>A: 请求生成阶段计划和首题
    A-->>S: 返回阶段计划和首题
    S->>M: 更新 stage_plan_json, current_question_id
    S-->>F: 返回首题和进度范围

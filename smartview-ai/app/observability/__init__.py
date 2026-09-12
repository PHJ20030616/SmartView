"""FastAPI 侧可观测性子包。

当前只包含 LLM 调用日志（llm_call_log）这一个能力，它也是本服务唯一允许写入的
数据库表——边界定义与理由见 develop_plan/plan_1.1.md §5.4 与 AGENTS.md。
"""

"""
内部数据模型模块

定义不直接暴露给外部 API 的内部数据结构，主要用于数据库实体映射。
"""
from datetime import datetime

from pydantic import BaseModel, ConfigDict


class InternalSchema(BaseModel):
    """
    内部模型基类

    配置为支持从 ORM 对象（如 SQLAlchemy 模型）自动转换。
    """
    model_config = ConfigDict(from_attributes=True)


class InternalAuditFields(InternalSchema):
    """
    审计字段模型

    包含创建和更新时间戳，用于跟踪数据的生命周期。

    属性:
        created_at: 创建时间
        updated_at: 最后更新时间
    """
    created_at: datetime | None = None
    updated_at: datetime | None = None

from datetime import datetime

from pydantic import BaseModel, ConfigDict


class InternalSchema(BaseModel):
    model_config = ConfigDict(from_attributes=True)


class InternalAuditFields(InternalSchema):
    created_at: datetime | None = None
    updated_at: datetime | None = None

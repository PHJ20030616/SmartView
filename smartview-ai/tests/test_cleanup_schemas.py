"""清理任务 MQ 消息模型校验测试（Task 7.2）。"""

from datetime import datetime, timezone

import pytest
from pydantic import ValidationError

from app.schemas.cleanup import CleanupResult, CleanupTask


def _task_payload() -> dict:
    return {
        "taskId": "00000000-0000-0000-0000-000000000301",
        "traceId": "00000000-0000-0000-0000-000000000031",
        "messageType": "CLEANUP_TASK",
        "schemaVersion": "1.0.0",
        "retryCount": 0,
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "bizType": "RESUME_FILE",
        "bizId": "88",
        "objectKey": "resumes/7/old-resume.pdf",
        "resumeProfileIds": ["101", "102"],
    }


def test_cleanup_task_valid() -> None:
    task = CleanupTask.model_validate(_task_payload())
    assert task.objectKey == "resumes/7/old-resume.pdf"
    assert task.resumeProfileIds == ["101", "102"]


def test_cleanup_task_allows_empty_profile_ids() -> None:
    """解析失败的简历没有画像：允许空列表，worker 仅清理 MinIO 文件。"""
    payload = _task_payload()
    payload["resumeProfileIds"] = []
    task = CleanupTask.model_validate(payload)
    assert task.resumeProfileIds == []


def test_cleanup_task_accepts_missing_profile_ids() -> None:
    """契约中 resumeProfileIds 非必填（兼容无画像场景）。"""
    payload = _task_payload()
    payload.pop("resumeProfileIds")
    task = CleanupTask.model_validate(payload)
    assert task.resumeProfileIds == []


def test_cleanup_task_rejects_unknown_biz_type() -> None:
    payload = _task_payload()
    payload["bizType"] = "INTERVIEW_SESSION"
    with pytest.raises(ValidationError):
        CleanupTask.model_validate(payload)


def test_cleanup_task_accepts_java_local_datetime_array() -> None:
    """兼容 Spring Jackson 将 LocalDateTime 编码成数组的历史消息格式。"""
    payload = _task_payload()
    payload["createdAt"] = [2026, 8, 20, 10, 30, 0, 123_000_000]
    task = CleanupTask.model_validate(payload)
    assert task.createdAt.year == 2026
    assert task.createdAt.microsecond == 123_000


def test_cleanup_result_success_requires_cleaned_count() -> None:
    base = {
        "taskId": "00000000-0000-0000-0000-000000000301",
        "traceId": "00000000-0000-0000-0000-000000000031",
        "messageType": "CLEANUP_RESULT",
        "schemaVersion": "1.0.0",
        "retryCount": 0,
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "bizType": "RESUME_FILE",
        "bizId": "88",
        "success": True,
    }
    with pytest.raises(ValidationError):
        CleanupResult.model_validate(base)
    result = CleanupResult.model_validate({**base, "cleanedProfileCount": 2})
    assert result.success is True
    assert result.cleanedProfileCount == 2


def test_cleanup_result_failure_requires_error_message() -> None:
    base = {
        "taskId": "00000000-0000-0000-0000-000000000301",
        "traceId": "00000000-0000-0000-0000-000000000031",
        "messageType": "CLEANUP_RESULT",
        "schemaVersion": "1.0.0",
        "retryCount": 0,
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "bizType": "RESUME_FILE",
        "bizId": "88",
        "success": False,
    }
    with pytest.raises(ValidationError):
        CleanupResult.model_validate(base)
    result = CleanupResult.model_validate({**base, "errorMessage": "MinIO 不可用"})
    assert result.errorMessage == "MinIO 不可用"

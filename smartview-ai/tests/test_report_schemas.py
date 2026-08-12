"""报告生成 MQ 消息模型校验测试。"""

import pytest
from app.schemas.report import ReportGenerateResult


def _result(**overrides: object) -> dict:
    payload = {
        "taskId": "00000000-0000-0000-0000-000000000501",
        "traceId": "00000000-0000-0000-0000-000000000051",
        "messageType": "REPORT_GENERATE_RESULT",
        "schemaVersion": "1.0.0",
        "retryCount": 0,
        "createdAt": "2026-08-12T00:00:00Z",
        "sessionId": "88",
        "success": True,
        "reportId": "5",
        "overallScore": 72,
        "readinessLevel": "READY",
        "roleFitScore": 80,
        "summary": "总体评价",
        "strengths": ["基础扎实"],
        "weaknesses": ["深度不足"],
        "riskPoints": ["项目描述空泛"],
        "suggestions": [{"topic": "并发", "reason": "薄弱", "resources": []}],
        "coverage": {
            "basicCoverage": 0.8,
            "projectCoverage": 1.0,
            "scenarioCoverage": 0.5,
        },
        "referenceAnswers": [
            {
                "questionId": "10",
                "answerType": "BASIC_KEY_POINTS",
                "referenceContent": "参考答案",
                "keyPoints": ["要点"],
                "tradeoffs": [],
            }
        ],
    }
    payload.update(overrides)
    return payload


def test_success_result_requires_full_content() -> None:
    result = ReportGenerateResult.model_validate(_result())
    assert result.success is True
    assert result.referenceAnswers[0].answerType == "BASIC_KEY_POINTS"


@pytest.mark.parametrize(
    "override",
    [
        {"reportId": None},
        {"overallScore": None},
        {"strengths": None},
        {"suggestions": None},
        {"referenceAnswers": None},
    ],
)
def test_success_result_rejects_missing_content(override: dict) -> None:
    with pytest.raises(ValueError):
        ReportGenerateResult.model_validate(_result(**override))


def test_failure_result_requires_error_message() -> None:
    payload = _result(success=False, errorMessage="LLM 服务暂时不可用")
    # 覆盖成功时必填字段为 None，避免残留触发校验
    for key in ("reportId", "overallScore", "readinessLevel", "roleFitScore",
                "summary", "strengths", "weaknesses", "riskPoints", "coverage",
                "referenceAnswers"):
        payload[key] = None
    result = ReportGenerateResult.model_validate(payload)
    assert result.success is False


def test_failure_result_rejects_missing_error_message() -> None:
    with pytest.raises(ValueError):
        ReportGenerateResult.model_validate(_result(success=False))

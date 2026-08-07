"""候选池生成 Pydantic 模型测试。

覆盖：请求/响应模型字段与契约一致、候选池响应不变量校验（失败必须带错误原因）。
"""

from uuid import uuid4

import pytest
from pydantic import ValidationError

from app.schemas.interview import (
    CandidatePoolItem,
    GenerateCandidatePoolRequest,
    GenerateCandidatePoolResponse,
    StagePlan,
)


def _request(**overrides) -> GenerateCandidatePoolRequest:
    base = dict(
        sessionId="1",
        questionId="11",
        roleDirection="JAVA_BACKEND",
        poolType="PRE_GENERATED",
        currentStage="BASIC",
        stagePlan=StagePlan(policy_version="1.0"),
        traceId=uuid4(),
    )
    base.update(overrides)
    return GenerateCandidatePoolRequest(**base)


def test_request_accepts_pre_generated_minimal() -> None:
    req = _request()
    assert req.poolType == "PRE_GENERATED"
    assert req.historyTopics == []


def test_request_accepts_follow_up_with_evaluation_facts() -> None:
    req = _request(
        poolType="FOLLOW_UP",
        sessionContext={"currentTopic": "Java 并发"},
        evaluationFacts={
            "score": 60,
            "level": "NORMAL",
            "missingPoints": ["未说明 volatile 语义"],
            "riskPoints": [{"category": "SHALLOW_DEPTH", "description": "回答空泛"}],
        },
    )
    assert req.poolType == "FOLLOW_UP"
    assert req.sessionContext.currentTopic == "Java 并发"
    assert req.evaluationFacts.score == 60


def test_response_invariant_failure_needs_error_message() -> None:
    with pytest.raises(ValidationError):
        GenerateCandidatePoolResponse(success=False, candidates=[], errorMessage="")


def test_response_success_with_candidate() -> None:
    resp = GenerateCandidatePoolResponse(
        success=True,
        candidates=[
            CandidatePoolItem(
                questionText="请解释 volatile 的可见性语义。",
                topic="Java 并发",
                stage="BASIC",
                candidateType="SAME_STAGE_SWITCH",
                sourceType="KNOWLEDGE_BASE",
                expectedPoints=["可见性", "禁止重排序"],
                targetPoint="Java 并发",
            )
        ],
    )
    assert resp.candidates[0].candidateType == "SAME_STAGE_SWITCH"


def test_response_success_accepts_without_error_message() -> None:
    resp = GenerateCandidatePoolResponse(success=True, candidates=[], errorMessage=None)
    assert resp.success is True
    assert resp.errorMessage is None


def test_response_failure_with_error_message_is_valid() -> None:
    resp = GenerateCandidatePoolResponse(success=False, candidates=[], errorMessage="生成失败")
    assert resp.success is False
    assert resp.errorMessage == "生成失败"

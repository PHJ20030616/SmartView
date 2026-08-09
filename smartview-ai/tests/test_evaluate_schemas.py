"""Task 5.4 评估请求/响应 Pydantic 模型测试。"""
import pytest
from uuid import uuid4

from app.schemas.interview import (
    CandidatePoolItem,
    EvaluateAnswerRequest,
    EvaluateAnswerResponse,
    SessionContext,
    StagePlan,
)


def _request(**overrides):
    base = dict(
        sessionId="1",
        questionId="11",
        answerText="volatile 保证可见性与禁止重排序",
        roleDirection="JAVA_BACKEND",
        questionText="volatile 的作用是什么？",
        expectedPoints=["可见性", "禁止指令重排"],
        stagePlan=StagePlan(),
        sessionContext=SessionContext(
            currentStage="BASIC", currentTopic="Java 并发", questionCount=2,
            stageCoverage={"BASIC": {"question_count": 1}},
        ),
        traceId=uuid4(),
    )
    base.update(overrides)
    return EvaluateAnswerRequest(**base)


def test_request_carries_evaluation_inputs():
    req = _request()
    assert req.questionText
    assert req.expectedPoints == ["可见性", "禁止指令重排"]
    assert req.sessionContext.currentStage == "BASIC"
    assert req.sessionContext.stageCoverage["BASIC"]["question_count"] == 1
    assert req.stagePlan is not None


def test_response_success_requires_score():
    with pytest.raises(ValueError):
        EvaluateAnswerResponse(success=True)


def test_response_failure_requires_error_message():
    with pytest.raises(ValueError):
        EvaluateAnswerResponse(success=False)


def test_response_carries_full_follow_up_candidates():
    resp = EvaluateAnswerResponse(
        success=True,
        score=85,
        level="GOOD",
        matchedPoints=["可见性"],
        missingPoints=[],
        followUpCandidates=[
            CandidatePoolItem(
                questionText="volatile 能保证原子性吗？",
                topic="Java 并发",
                stage="BASIC",
                candidateType="FOLLOW_UP",
                expectedPoints=["原子性区分"],
            )
        ],
    )
    assert resp.followUpCandidates[0].candidateType == "FOLLOW_UP"
    assert resp.followUpCandidates[0].stage == "BASIC"

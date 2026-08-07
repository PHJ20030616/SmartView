"""stage_controller 确定性生成目标计算测试。

覆盖：预生成（同阶段换题缺主题回退、下一阶段入口、最后阶段无下一阶段）、
追问（深度门控、得分门控、风险追问封顶）。
"""

from app.nodes.stage_controller import compute_generation_targets

# BASIC→PROJECT→SCENARIO 三阶段计划（与 docs/interview-policy.md 2.2 结构一致）
_PLAN = {
    "policy_version": "1.0",
    "stages": [
        {
            "stage": "BASIC",
            "min_questions": 3,
            "max_questions": 8,
            "required_topics": ["Java 并发", "JVM", "Spring"],
            "max_follow_up_depth": 2,
        },
        {
            "stage": "PROJECT",
            "min_questions": 2,
            "max_questions": 6,
            "required_topics": ["电商平台"],
            "max_follow_up_depth": 3,
        },
        {
            "stage": "SCENARIO",
            "min_questions": 2,
            "max_questions": 6,
            "required_topics": ["系统设计"],
            "max_follow_up_depth": 2,
        },
    ],
}


def _state(**overrides) -> dict:
    base = dict(
        pool_type="PRE_GENERATED",
        current_stage="BASIC",
        stage_plan=_PLAN,
        stage_coverage={},
        current_topic=None,
        history_topics=[],
        evaluation_facts=None,
    )
    base.update(overrides)
    return base


def test_pre_generated_missing_topics_drives_same_stage_switch() -> None:
    targets = compute_generation_targets(_state())["generation_targets"]
    same_stage = [t for t in targets if t["candidateType"] == "SAME_STAGE_SWITCH"]
    # 无覆盖时取 required_topics 前 2
    assert [t["topic"] for t in same_stage] == ["Java 并发", "JVM"]
    assert all(t["stage"] == "BASIC" for t in same_stage)


def test_pre_generated_covered_topics_excluded() -> None:
    targets = compute_generation_targets(
        _state(
            stage_coverage={
                "BASIC": {"covered_topics": ["Java 并发"], "missing_topics": ["JVM"]}
            }
        )
    )["generation_targets"]
    same_stage = [t for t in targets if t["candidateType"] == "SAME_STAGE_SWITCH"]
    assert [t["topic"] for t in same_stage] == ["JVM"]


def test_pre_generated_all_covered_falls_back_excluding_current_topic() -> None:
    targets = compute_generation_targets(
        _state(
            current_topic="Spring",
            stage_coverage={
                "BASIC": {
                    "covered_topics": ["Java 并发", "JVM", "Spring"],
                    "missing_topics": [],
                }
            },
        )
    )["generation_targets"]
    same_stage = [t for t in targets if t["candidateType"] == "SAME_STAGE_SWITCH"]
    # 全部覆盖时回退 required_topics 剔除当前主题
    assert "Spring" not in [t["topic"] for t in same_stage]


def test_pre_generated_next_stage_entry() -> None:
    targets = compute_generation_targets(_state())["generation_targets"]
    next_entry = [t for t in targets if t["candidateType"] == "NEXT_STAGE_ENTRY"]
    assert [t["topic"] for t in next_entry] == ["电商平台"]
    assert all(t["stage"] == "PROJECT" for t in next_entry)


def test_pre_generated_last_stage_has_no_next_entry() -> None:
    targets = compute_generation_targets(
        _state(current_stage="SCENARIO")
    )["generation_targets"]
    assert not [t for t in targets if t["candidateType"] == "NEXT_STAGE_ENTRY"]


def test_follow_up_depth_reached_returns_empty() -> None:
    targets = compute_generation_targets(
        _state(
            pool_type="FOLLOW_UP",
            current_stage="BASIC",
            current_topic="Java 并发",
            evaluation_facts={"score": 75, "matchedPoints": ["定义清晰"]},
            stage_coverage={
                "BASIC": {"current_topic_follow_up_count": 2}  # 已达 max_follow_up_depth
            },
        )
    )["generation_targets"]
    assert targets == []


def test_follow_up_score_below_40_returns_empty() -> None:
    targets = compute_generation_targets(
        _state(
            pool_type="FOLLOW_UP",
            current_stage="BASIC",
            current_topic="Java 并发",
            evaluation_facts={"score": 30},
        )
    )["generation_targets"]
    assert targets == []


def test_follow_up_medium_score_generates_gap_and_risk() -> None:
    targets = compute_generation_targets(
        _state(
            pool_type="FOLLOW_UP",
            current_stage="BASIC",
            current_topic="Java 并发",
            evaluation_facts={
                "score": 60,
                "missingPoints": ["未说明 volatile 语义"],
                "riskPoints": [{"category": "SHALLOW_DEPTH", "description": "回答空泛"}],
            },
        )
    )["generation_targets"]
    assert len(targets) == 2
    assert [t["candidateType"] for t in targets] == ["FOLLOW_UP", "FOLLOW_UP"]
    basis_types = {t["basisType"] for t in targets}
    assert basis_types == {"missing", "risk"}


def test_follow_up_high_score_generates_deep_without_risk() -> None:
    targets = compute_generation_targets(
        _state(
            pool_type="FOLLOW_UP",
            current_stage="BASIC",
            current_topic="Java 并发",
            evaluation_facts={
                "score": 80,
                "matchedPoints": ["准确说出 happens-before"],
            },
        )
    )["generation_targets"]
    assert len(targets) == 1
    assert targets[0]["basisType"] == "deep"
    assert targets[0]["basis"] == "准确说出 happens-before"

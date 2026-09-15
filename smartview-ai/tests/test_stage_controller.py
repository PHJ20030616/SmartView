"""stage_controller 确定性生成目标计算测试。

覆盖：预生成（同阶段换题缺主题回退、下一阶段入口、最后阶段无下一阶段）、
追问（深度门控、空答/弱答跳过、GAP+DEEP 两型目标且不读评估事实）。
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
        # 追问目标计算需要回答文本（空答/弱答跳过）；默认给一段实质回答
        answer_text="volatile 保证可见性并禁止指令重排",
        question_text="volatile 的作用？",
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


def test_follow_up_generates_gap_and_deep_without_evaluation_facts() -> None:
    """与评估并行：不提供评估事实时仍产出"补缺口 + 深挖"两型目标。"""
    targets = compute_generation_targets(
        _state(
            pool_type="FOLLOW_UP",
            current_stage="BASIC",
            current_topic="Java 并发",
            evaluation_facts=None,
        )
    )["generation_targets"]
    assert [t["followUpKind"] for t in targets] == ["GAP", "DEEP"]
    assert all(t["candidateType"] == "FOLLOW_UP" for t in targets)
    assert all(t["topic"] == "Java 并发" for t in targets)


def test_follow_up_ignores_score_from_evaluation_facts() -> None:
    """得分门控已移到 Spring 决策侧：生成侧不再按得分过滤（低于 40 也照样生成两型）。"""
    targets = compute_generation_targets(
        _state(
            pool_type="FOLLOW_UP",
            current_stage="BASIC",
            current_topic="Java 并发",
            evaluation_facts={"score": 15, "level": "WEAK"},
        )
    )["generation_targets"]
    assert [t["followUpKind"] for t in targets] == ["GAP", "DEEP"]


def test_follow_up_blank_answer_returns_empty() -> None:
    targets = compute_generation_targets(
        _state(
            pool_type="FOLLOW_UP",
            current_stage="BASIC",
            current_topic="Java 并发",
            answer_text="   ",
        )
    )["generation_targets"]
    assert targets == []


def test_follow_up_weak_keyword_answer_returns_empty() -> None:
    """明确表示不会的回答没有可追问内容，保持零 LLM 调用。"""
    targets = compute_generation_targets(
        _state(
            pool_type="FOLLOW_UP",
            current_stage="BASIC",
            current_topic="Java 并发",
            answer_text="不太清楚，没学过",
        )
    )["generation_targets"]
    assert targets == []

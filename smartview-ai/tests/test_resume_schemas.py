from pathlib import Path
from uuid import uuid4

import pytest
import yaml
from pydantic import ValidationError

from app.main import create_app
from app.schemas.resume import (
    ParseResumeRequest,
    ParseResumeResponse,
    ResumeStructuredData,
)

CONTRACT_PATH = Path(__file__).parents[2] / "contracts" / "ai-api" / "openapi.yaml"


def _without_documentation_fields(value):
    """比较契约结构时忽略标题、说明和示例等非行为字段。"""
    if isinstance(value, dict):
        return {
            key: _without_documentation_fields(item)
            for key, item in value.items()
            if key not in {"title", "description", "example"}
        }
    if isinstance(value, list):
        return [_without_documentation_fields(item) for item in value]
    return value


def test_parse_resume_request_accepts_pdf_url_and_trace_id() -> None:
    request = ParseResumeRequest(
        fileUrl="https://minio.example.com/resumes/demo.pdf",
        mimeType="application/pdf",
        traceId=uuid4(),
    )

    assert request.mimeType == "application/pdf"
    assert request.fileUrl.scheme == "https"


def test_parse_resume_request_rejects_non_pdf_mime_type() -> None:
    with pytest.raises(ValidationError):
        ParseResumeRequest(
            fileUrl="https://minio.example.com/resumes/demo.docx",
            mimeType="application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            traceId=uuid4(),
        )


def test_resume_structured_data_defaults_lists_to_empty_arrays() -> None:
    data = ResumeStructuredData(candidateName="张三")

    assert data.education == []
    assert data.workExperience == []
    assert data.projectExperience == []
    assert data.skills == []


def test_successful_parse_response_requires_raw_text() -> None:
    with pytest.raises(ValidationError):
        ParseResumeResponse(success=True)


def test_resume_contract_property_names_match_pydantic_models() -> None:
    contract = yaml.safe_load(CONTRACT_PATH.read_text(encoding="utf-8"))
    schemas = contract["components"]["schemas"]

    assert set(schemas["ParseResumeRequest"]["properties"]) == set(
        ParseResumeRequest.model_fields
    )
    assert set(schemas["ParseResumeResponse"]["properties"]) == set(
        ParseResumeResponse.model_fields
    )
    assert set(schemas["ResumeStructuredData"]["properties"]) == set(
        ResumeStructuredData.model_fields
    )


def test_fastapi_resume_openapi_matches_contract_operation() -> None:
    contract_path = (
        Path(__file__).parents[2] / "contracts" / "ai-api" / "openapi.yaml"
    )
    contract = yaml.safe_load(contract_path.read_text(encoding="utf-8"))
    runtime_operation = create_app().openapi()["paths"]["/api/v1/resume/parse"]["post"]
    contract_operation = contract["paths"]["/api/v1/resume/parse"]["post"]

    assert create_app().openapi()["openapi"] == contract["openapi"]
    assert runtime_operation == contract_operation


def test_fastapi_resume_schemas_match_contract_behavior() -> None:
    contract = yaml.safe_load(CONTRACT_PATH.read_text(encoding="utf-8"))
    runtime = create_app().openapi()
    schema_names = (
        "ContactInfo",
        "EducationExperience",
        "WorkExperience",
        "ProjectExperience",
        "ParseResumeRequest",
        "ParseResumeResponse",
        "ErrorResponse",
    )

    assert runtime["components"]["securitySchemes"] == contract["components"]["securitySchemes"]
    for schema_name in schema_names:
        assert _without_documentation_fields(
            runtime["components"]["schemas"][schema_name]
        ) == _without_documentation_fields(contract["components"]["schemas"][schema_name])


def test_resume_contract_defines_each_reusable_schema_once() -> None:
    contract_text = CONTRACT_PATH.read_text(encoding="utf-8")
    schema_names = (
        "ContactInfo",
        "EducationExperience",
        "WorkExperience",
        "ProjectExperience",
        "ResumeStructuredData",
        "ParseResumeRequest",
        "ParseResumeResponse",
    )

    # PyYAML 默认会静默覆盖重复键，因此直接检查原始契约以防生成代码读取到错误版本。
    for schema_name in schema_names:
        assert contract_text.count(f"\n    {schema_name}:") == 1


def test_parse_resume_response_reuses_nested_contract_schemas() -> None:
    contract = yaml.safe_load(CONTRACT_PATH.read_text(encoding="utf-8"))
    properties = contract["components"]["schemas"]["ParseResumeResponse"]["properties"]

    assert properties["contactInfo"]["allOf"][0]["$ref"].endswith("/ContactInfo")
    assert properties["education"]["items"]["$ref"].endswith("/EducationExperience")
    assert properties["workExperience"]["items"]["$ref"].endswith("/WorkExperience")
    assert properties["projectExperience"]["items"]["$ref"].endswith(
        "/ProjectExperience"
    )

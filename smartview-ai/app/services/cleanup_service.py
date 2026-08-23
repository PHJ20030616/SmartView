"""清理任务服务（Task 7.2 软删除与物理清理）。

负责删除简历删除后的外部派生数据：
1. MinIO 对象（简历原始文件）；
2. Chroma 向量（简历画像的全部历史版本切片）。

外部依赖异常统一转换为可重试的 AppError（MINIO_UNAVAILABLE / VECTOR_STORE_UNAVAILABLE），
由 cleanup worker 的消费层决定是否按指数退避重投。
"""

from __future__ import annotations

import logging
from typing import Any

from app.core.config import Settings, get_settings
from app.core.errors import AppError

log = logging.getLogger(__name__)


class MinioCleaner:
    """MinIO 对象删除封装；客户端惰性创建，便于单元测试无需真实 MinIO。"""

    def __init__(self, settings: Settings | None = None) -> None:
        self.settings = settings or get_settings()

    def _build_client(self) -> Any:
        """构建 MinIO 客户端；导入放在方法内以便单元测试无需安装/启动 MinIO。

        minio SDK 的 Minio.__init__ 会自行拼接 http:// 或 https:// 前缀，
        因此这里剥离配置中的 scheme 后传入，并按 scheme 推导 secure 开关
        （否则 "http://" + "http://localhost:9000" 会解析出非法路径）。
        """
        from minio import Minio

        endpoint = self.settings.minio_endpoint
        secure = True
        if "://" in endpoint:
            scheme, endpoint = endpoint.split("://", 1)
            secure = scheme.lower() == "https"

        return Minio(
            endpoint,
            access_key=self.settings.minio_access_key,
            secret_key=self.settings.minio_secret_key.get_secret_value(),
            region=self.settings.minio_region or None,
            secure=secure,
        )

    def delete_object(self, object_key: str) -> None:
        """删除指定对象；对象不存在时按已清理处理（幂等）。"""
        client = self._build_client()
        try:
            try:
                # S3 规范中删除不存在的对象返回 204，remove_object 本身幂等；
                # 个别实现会抛 NoSuchKey，同样视为已清理成功。
                client.remove_object(self.settings.minio_bucket, object_key)
            except Exception as exc:
                if _is_no_such_key(exc):
                    log.info("MinIO 对象不存在，按已清理处理，objectKey=%s", object_key)
                    return
                raise
            log.info("MinIO 对象已删除，objectKey=%s", object_key)
        except AppError:
            raise
        except Exception as exc:
            log.exception(
                "MinIO 对象删除失败，objectKey=%s",
                object_key,
            )
            raise AppError(
                "MinIO 暂时不可用，清理任务稍后重试",
                code="MINIO_UNAVAILABLE",
            ) from exc


def _is_no_such_key(exc: Exception) -> bool:
    """判断异常是否为“对象不存在”（NoSuchKey / NotFound）。

    兼容 minio SDK 抛出的 S3Error（code=NoSuchKey）与部分版本抛出的
    S3Error 子类/包装异常，避免把幂等成功误判为可重试故障。
    """
    error_code = getattr(exc, "code", None)
    if error_code in {"NoSuchKey", "NotFound"}:
        return True
    message = str(exc).lower()
    return "nosuchkey" in message or "no such key" in message or "not found" in message


def cleanup_resume_resources(
    object_key: str,
    resume_profile_ids: list[str],
    *,
    settings: Settings | None = None,
) -> int:
    """删除一份简历关联的全部外部派生数据，返回实际处理的画像数量。

    执行顺序：先删 MinIO 原始文件，再逐个删除画像的 Chroma 向量。
    每个步骤独立失败并抛出可重试的 AppError；已经成功删除的部分不会回滚，
    重试时依赖删除操作的幂等性保证最终一致。
    """
    settings = settings or get_settings()
    MinioCleaner(settings).delete_object(object_key)

    # 惰性导入向量删除，复用 ResumeVectorizer 的按画像 ID 幂等删除能力；
    # Chroma 异常统一转换为 VECTOR_STORE_UNAVAILABLE 以便消费层重试。
    from app.services.resume_vectorizer import ResumeVectorizer

    vectorizer = ResumeVectorizer(settings)
    cleaned_count = 0
    for profile_id in resume_profile_ids:
        try:
            vectorizer.delete_profile_vectors(int(profile_id))
            cleaned_count += 1
        except AppError:
            raise
        except Exception as exc:
            log.exception(
                "简历画像向量删除失败，profileId=%s",
                profile_id,
            )
            raise AppError(
                "向量库暂时不可用，清理任务稍后重试",
                code="VECTOR_STORE_UNAVAILABLE",
            ) from exc

    log.info(
        "简历清理完成，objectKey=%s, profileCount=%s",
        object_key,
        cleaned_count,
    )
    return cleaned_count

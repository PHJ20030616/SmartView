#!/usr/bin/env bash
# 文档一致性检查：守住"文档里描述的类名、模块名与命令必须真实存在"这条底线。
#
# 背景：README 与 AGENTS.md 曾长期描述 AiServiceClient、ObjectStorageService、
# VectorStoreService 三个并不存在的类，以及不存在的 Maven Wrapper 命令 ./mvnw。
# 这类漂移不会让任何单元测试变红，却能直接误导接手的人，因此单列一道 CI 门禁。
#
# 维护约定：新增或重命名架构组件时，必须同步修改下面的清单与文档，否则 CI 会失败；
# 反向说，如果这里报了错但你确认文档是正确的，那说明代码没有跟上文档。
set -euo pipefail

# 良构路径：脚本可能从仓库根或任意目录调用，用脚本位置反推仓库根。
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

# 形如 "<文件名>:<禁止出现的正则>"，正则使用 grep -E 语法。
FORBIDDEN=(
  "README.md:AiServiceClient|ObjectStorageService|VectorStoreService"
  "README.md:\./mvnw"
  "AGENTS.md:AiServiceClient|smartview-backend"
  "AGENTS.md:\./mvnw"
)

# 待检查文件清单。注意 AGENTS.md 被 .gitignore 排除（决策见提交 2fe97db
# "从版本控制中移除 AGENTS.md 和 develop_plan/ 目录"），因此它只存在于本地工作区，
# CI 检出中不会有这个文件。
# 关键点：缺失时必须显式声明"未检查"，不能让针对它的规则悄悄失效——
# 静默跳过会让人误以为 AGENTS.md 也通过了门禁，那比不检查更危险。
scan_files=()
for candidate in README.md AGENTS.md; do
  if [ -f "$candidate" ]; then
    scan_files+=("$candidate")
  else
    echo "::notice::$candidate 不在当前工作区（未纳入版本管理），本轮跳过对它的检查"
  fi
done

failed=0
for rule in "${FORBIDDEN[@]}"; do
  file="${rule%%:*}"
  pattern="${rule#*:}"
  [ -f "$file" ] || continue
  # grep 无匹配时退出码为 1，此处用 || true 兜住，避免被 set -e 提前中断。
  matches="$(grep -nE "$pattern" "$file" || true)"
  if [ -n "$matches" ]; then
    echo "::error file=$file::检测到与实现不符的文档描述（正则：$pattern）"
    echo "$matches"
    failed=1
  fi
done

if [ "$failed" -ne 0 ]; then
  echo "文档一致性检查未通过：请把上述描述改成真实存在的类名、模块名或命令。"
  exit 1
fi

# ---------------------------------------------------------------------------
# 文档索引可达性：README/AGENTS 里提到的 docs/*.md 必须真实存在。
#
# 背景：README 曾索引 docs/local-development.md 而该文件不存在。这条漂移不只是"链接失效"，
# 它让"环境变量该怎么配"无处可查，间接掩盖了 Spring Boot 从未读取 .env 的缺陷（见 Task 11.0）。
# 这里自动提取而非硬编码清单：新增文档链接时无需改脚本，漂移自然被覆盖。
#
# 例外：README 明确标注为"规划中"的条目允许暂时不存在——这类文档是有意留白，
# 不是漂移。豁免依据写在被引用行自身（同一行含"规划中"），而不是脚本里的白名单：
# 这样文档自己声明了状态，也不会出现"白名单里躺着一个早已创建的文档"这种反向漂移。
# 新建这类文档后，记得把行内"（规划中）"标记去掉，该条目随即纳入强校验。
# ---------------------------------------------------------------------------
PLANNED_MARKER="规划中"
for doc_file in "${scan_files[@]}"; do
  while IFS= read -r entry; do
    [ -n "$entry" ] || continue
    line_no="${entry%%:*}"
    line_text="${entry#*:}"
    # 一行里可能引用多个文档，逐个取出校验。
    while read -r doc; do
      [ -n "$doc" ] || continue
      if [ -f "$doc" ]; then
        continue
      fi
      case "$line_text" in
        *"$PLANNED_MARKER"*) echo "跳过（标注为规划中）：$doc_file:$line_no -> $doc" ;;
        *)
          echo "::error file=$doc_file,line=$line_no::$doc_file 索引了 $doc，但该文件不存在"
          failed=1
          ;;
      esac
    done < <(printf '%s\n' "$line_text" | grep -ohE 'docs/[A-Za-z0-9_./-]+\.md' | sort -u || true)
  done < <(grep -nE 'docs/[A-Za-z0-9_./-]+\.md' "$doc_file" || true)
done

if [ "$failed" -ne 0 ]; then
  echo "文档一致性检查未通过：请补上缺失的文件，或删除指向它的索引条目。"
  exit 1
fi

echo "文档一致性检查通过。"

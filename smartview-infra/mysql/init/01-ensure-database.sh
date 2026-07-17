#!/usr/bin/env bash
set -euo pipefail

# 该脚本只会在 MySQL 数据卷首次初始化时执行。
# 官方镜像负责创建用户和授权；这里补充字符集约束和数据库名安全校验。
database_name="${MYSQL_DATABASE:-smartview}"

if [[ ! "${database_name}" =~ ^[A-Za-z0-9_]+$ ]]; then
  echo "MYSQL_DATABASE 只能包含字母、数字和下划线" >&2
  exit 1
fi

mysql --protocol=socket -uroot -p"${MYSQL_ROOT_PASSWORD}" <<SQL
ALTER DATABASE \`${database_name}\`
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;
SQL

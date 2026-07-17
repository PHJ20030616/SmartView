# MySQL 初始化脚本

本目录会挂载到 MySQL 官方镜像的 `/docker-entrypoint-initdb.d/`，仅在 `mysql_data` 数据卷首次初始化时执行。

当前脚本用于校验 `MYSQL_DATABASE` 的命名安全性，并确保应用数据库使用 `utf8mb4` 字符集和 `utf8mb4_0900_ai_ci` 排序规则。账号创建、密码设置和授权由 MySQL 官方镜像根据环境变量完成。

如果需要重新执行初始化脚本，需要先停止 Compose 并删除 `mysql_data` 数据卷；这会清空本地数据库数据。

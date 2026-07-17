# MinIO 本地开发说明

MinIO API 默认地址为 `http://localhost:9000`，Console 默认地址为 `http://localhost:9001`。

默认本地登录账号来自 `.env.example`：

- 用户名：`smartview`
- 密码：`smartview_minio_password`

本任务只提供对象存储基础服务和登录能力，不自动创建业务存储桶。后续如果需要初始化存储桶或策略，应在本目录新增脚本，并在计划中明确是否通过长期运行服务、一次性初始化容器或手动命令执行。

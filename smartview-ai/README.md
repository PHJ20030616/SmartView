# SmartView AI 服务

FastAPI AI 服务基础工程，只对 Spring Boot 后端开放能力接口。React 前端不得直接调用本服务。

## 本地启动

```bash
cd smartview-ai
python -m venv venv
venv\Scripts\activate
python -m pip install -e ".[test,ocr]"
python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

生产环境必须安装 `.[ocr]`，否则扫描型 PDF 在文本层不可用时会返回可读的 OCR 依赖错误。
同时请在 `.env` 中配置 `AI_SERVICE_API_KEY`、`DEEPSEEK_API_KEY` 和
`RESUME_ALLOWED_HOSTS`，其中后者应填写 MinIO/S3 对象存储域名白名单。

启动后访问：

- 健康检查：`http://127.0.0.1:8000/api/v1/health`
- OpenAPI 文档：`http://127.0.0.1:8000/docs`

## 测试

```bash
cd smartview-ai
python -m pytest
```

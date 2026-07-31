"""pytest 全局配置：测试环境关闭文件日志，避免污染仓库 logs 目录。"""
import os

os.environ["LOG_FILE_ENABLED"] = "false"

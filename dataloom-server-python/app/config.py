import os
from pathlib import Path

# 项目根目录
BASE_DIR = Path(__file__).resolve().parent.parent

# API 服务配置
PORT = int(os.getenv("PORT", 9191))
HOST = os.getenv("HOST", "0.0.0.0")

# 文件上传配置
UPLOAD_DIR = BASE_DIR / "upload"
# 确保上传目录存在
UPLOAD_DIR.mkdir(parents=True, exist_ok=True)

# 数据库配置
# 本地使用 SQLite，以对标 Java 版本中 H2 数据库的零配置启动
DB_DIR = BASE_DIR / "data"
DB_DIR.mkdir(parents=True, exist_ok=True)
DATABASE_URL = os.getenv("DATABASE_URL", f"sqlite:///{DB_DIR}/excel-demo.db")

# 分块配置 (默认每个数据块存储 1000 行)
CHUNK_SIZE = int(os.getenv("CHUNK_SIZE", 1000))

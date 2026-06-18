# DataLoom Python 后端服务 (FastAPI)

这是一个基于 Python FastAPI 框架重写的 DataLoom 后端服务，完全对标并兼容原 Spring Boot 版的 REST API 和数据库分块（Chunk）设计。

## 特性

- **FastAPI 驱动**：比传统 Python Web 框架更快，自带 Swagger 交互式 API 文档。
- **SQLite 数据库**：采用文件存储模式，开箱即用，无需配置复杂的外部数据库。
- **1000行分块**：完全继承原版的 Excel 分块（Chunk）存储思想，十万级数据拉取和保存无压力。
- **双读取解析**：使用 `openpyxl` 引擎，解析时同时加载数值和公式，零漏失恢复。
- **混合保存策略**：编辑单元格仅更新对应分块（`/cells/batch`），结构发生变化时触发事务全量更新（`/workbook`）。

---

## 快速开始

### 1. 前置条件

确保本地安装了 **Python 3.8+**。

### 2. 初始化虚拟环境与依赖安装

在 `dataloom-server-python` 目录下执行以下命令：

```bash
# 1. 创建 Python 虚拟环境 (venv)
python -m venv venv

# 2. 激活虚拟环境
# Windows (cmd):
venv\Scripts\activate.bat
# Windows (PowerShell):
venv\Scripts\Activate.ps1
# macOS/Linux:
source venv/bin/activate

# 3. 安装依赖项
pip install -r requirements.txt
```

### 3. 运行服务

```bash
# 在 Python 虚拟环境下启动服务
python -m app.main
```

或者使用 uvicorn 命令行启动：
```bash
uvicorn app.main:app --host 0.0.0.0 --port 9191 --reload
```

服务将启动在 `http://localhost:9191`，与前端 Vite 代理完美对接。

---

## API 接口文档

运行服务后，访问以下地址查看自动生成的 API 文档并进行接口测试：
- **Swagger UI**: `http://localhost:9191/docs`
- **ReDoc**: `http://localhost:9191/redoc`

---

## 目录结构

```
dataloom-server-python/
├── app/
│   ├── main.py                 # FastAPI 入口，配置 CORS 与路由
│   ├── config.py               # 基础配置文件
│   ├── database.py             # 数据库引擎与 Session 初始化
│   ├── models.py               # SQLAlchemy 模型 (ExcelDocument, ExcelSheet, ExcelSheetChunk)
│   ├── schemas.py              # Pydantic 校验模型
│   ├── routers/
│   │   ├── file.py             # 文件上传与解析 API (/api/excel/upload)
│   │   └── document.py         # 文档管理与保存 API
│   └── services/
│       ├── parser_service.py   # Excel (openpyxl) 双模式分块解析逻辑
│       ├── document_service.py # 文档 CRUD 数据库操作
│       └── sheet_service.py    # 工作簿保存与分块更新逻辑
├── requirements.txt            # 依赖声明
└── README.md                   # 说明文档
```

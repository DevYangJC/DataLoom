# DataLoom

DataLoom 是一个在线 Excel 协作编辑系统的最小化 Demo。项目包含 Spring Boot 后端和 Vue 2 前端，可以完成 Excel 上传、解析、在线编辑、保存和导出。

本仓库已按公开发布场景整理：本地数据库、上传文件、依赖目录、构建产物和临时产物不会提交到 GitHub。

## 功能特性

- 上传 `.xlsx` / `.xls` 文件。
- 将 Excel 内容解析为 Luckysheet 可渲染的数据。
- 在浏览器中查看文档列表并打开表格。
- 在线编辑单元格，支持手动保存和自动保存。
- 将编辑后的表格导出为 `.xlsx`。
- 使用 H2 文件数据库，适合本地快速运行和演示。

## 技术栈

| 层级 | 技术 |
| --- | --- |
| 后端 | Spring Boot 2.1.15, Java 8 |
| ORM | MyBatis-Plus 3.3.2 |
| 数据库 | H2 File Database |
| Excel 解析 | Apache POI 4.1.2 |
| Excel 导出 | EasyExcel 2.2.11 |
| 前端 | Vue 2.6, Vue Router, Element UI |
| 表格引擎 | Luckysheet 2.1.13 |

## 目录结构

```text
DataLoom/
|-- excel-service-demo/        # Spring Boot 后端
|   |-- pom.xml
|   `-- src/main/
|       |-- java/com/demo/excel/
|       |   |-- controller/    # 上传、导出、文档接口
|       |   |-- entity/        # 数据实体
|       |   |-- mapper/        # MyBatis-Plus Mapper
|       |   `-- service/       # Excel 解析和文档服务
|       `-- resources/
|           |-- application.yml
|           `-- schema.sql
|-- excel-web-demo/            # Vue 前端
|   |-- package.json
|   |-- vue.config.js
|   `-- src/
|       |-- api/               # API 封装
|       |-- router/            # 路由配置
|       |-- utils/             # 导出工具
|       `-- views/             # 文档列表和表格编辑页
|-- .gitignore
`-- README.md
```

## 环境要求

- JDK 8
- Maven 3.x
- Node.js 和 npm

## 本地运行

启动后端：

```bash
cd excel-service-demo
mvn spring-boot:run
```

后端默认运行在：

```text
http://localhost:9191
```

启动前端：

```bash
cd excel-web-demo
npm install
npm run serve
```

前端默认运行在：

```text
http://localhost:8081
```

前端代理配置位于 `excel-web-demo/vue.config.js`，`/api` 请求会转发到后端 `9191` 端口。

## H2 数据库

后端启动后可以访问 H2 控制台：

```text
http://localhost:9191/h2-console
```

默认连接信息：

```text
JDBC URL: jdbc:h2:file:./data/excel-demo
User Name: sa
Password: 留空
```

数据库文件会生成在 `excel-service-demo/data/`，该目录已被 Git 忽略。

## API 概览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/excel/upload` | 上传并解析 Excel |
| `GET` | `/api/excel/document/list` | 获取文档列表 |
| `GET` | `/api/excel/document/{id}` | 获取文档详情和表格 JSON |
| `POST` | `/api/excel/document/{id}/save` | 保存文档快照 |
| `GET` | `/api/excel/{id}/export` | 导出 `.xlsx` 文件 |
| `DELETE` | `/api/excel/document/{id}` | 软删除文档 |

## 发布说明

以下本地文件不会提交到 GitHub：

- `excel-service-demo/data/`
- `excel-service-demo/upload/`
- `excel-service-demo/src/main/resources/upload/`
- `excel-service-demo/upload_response*.json`
- `excel-web-demo/node_modules/`
- `excel-web-demo/artifacts/`
- `excel-service-demo/target/`
- IDE 文件、日志、本地数据库和构建产物

发布前建议检查：

```bash
git status --short
```

待提交内容应只包含源码、配置、锁文件、`.gitignore` 和 `README.md`。

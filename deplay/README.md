# DataLoom 部署说明

本目录提供 DataLoom 的 Docker Compose 部署文件，适用于当前仓库结构：

- `dataloom-server`：Spring Boot 后端，端口 `9191`
- `dataloom-web`：Vue 3 + Vite + Element Plus 前端，通过 Nginx 暴露端口 `8081`

## 前置要求

- Docker 24+
- Docker Compose v2+

## 一键启动

在仓库根目录执行：

```bash
docker compose -f deplay/docker-compose.yml up -d --build
```

启动后访问：

- 前端页面：http://localhost:8081
- 后端接口：http://localhost:9191/api/excel
- H2 Console：http://localhost:9191/h2-console

## 数据持久化

Compose 会创建两个 Docker volume：

- `dataloom_data`：保存 H2 数据库文件
- `dataloom_uploads`：保存上传的 Excel 文件

停止服务不会删除数据：

```bash
docker compose -f deplay/docker-compose.yml down
```

如需彻底清空数据：

```bash
docker compose -f deplay/docker-compose.yml down -v
```

## 常用命令

查看日志：

```bash
docker compose -f deplay/docker-compose.yml logs -f
```

只重启后端：

```bash
docker compose -f deplay/docker-compose.yml restart dataloom-server
```

重新构建并启动：

```bash
docker compose -f deplay/docker-compose.yml up -d --build
```

## 服务说明

前端容器使用 Nginx 托管 Vite 构建产物，并将 `/api/` 请求反向代理到后端容器：

```text
Browser -> http://localhost:8081
       -> Nginx
       -> /api/* proxy to dataloom-server:9191
```

后端容器使用 H2 文件数据库，默认数据库路径为：

```text
/app/data/excel-demo
```

上传文件默认保存到：

```text
/app/upload
```

这两个路径都已经挂载到 Docker volume。

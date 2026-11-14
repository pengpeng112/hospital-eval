# TASK-19：发布到生产服务器 10.10.8.75（ai病历质控）

| 项 | 内容 |
| :--- | :--- |
| 排期 | Day 10 · 第 1 次 |
| 依赖 | TASK-18 核查通过；用户已完成 SSH 免密配置（见 `03` 手册 2.3 节） |
| 预计改动 | 6~8 个部署文件 |
| 是否连数据库 / 服务器 | **连 10.10.8.75**（仅 `/opt/hospital-eval/`）；生产后端只读连 Oracle |

## 1. 目标
在**不影响** 10.10.8.75 上已有“AI 病历质控”服务的前提下，把 PostgreSQL、后端、前端（Nginx）、kkFileView 以 `eval-` 前缀容器部署到 `/opt/hospital-eval/`，并迁移本机开发库数据，完成上线验证。

## 2. 开工前必读
1. `AGENTS.md` 第三节第 4 条（**服务器共存红线**）
2. `03_AI长任务分批执行与回执核查手册.md` 第 2.3 节
3. `04_开发契约与编码规范.md` 第 14 节
4. 本文件

## 3. 前置检查（任一失败立即停止并报告，**不得**尝试用密码登录）
```powershell
ssh -o BatchMode=yes -o ConnectTimeout=10 hospital-eval-75 "echo SSH_OK; hostname; whoami"
```
- 输出必须含 `SSH_OK`；失败 → 回执写“请用户按 03 手册 2.3 节配置免密登录”后停止。

## 4. 允许新建 / 修改的文件
- 本地：`deploy/docker-compose.prod.yml`、`deploy/nginx/eval.conf`、`hospital-eval-web/Dockerfile`、`deploy/scripts/check_host_75.sh`、`deploy/scripts/deploy_75.ps1`、`deploy/scripts/pg_migrate.ps1`、`scripts/smoke/PROD_READONLY.ps1`、`docs/reports/10.10.8.75部署记录.md`、`docs/task_receipts/TASK_19_回执.md`
- 服务器：**只允许**在 `/opt/hospital-eval/` 下创建/修改文件；**只允许**操作名字以 `eval-` 开头的容器和名为 `hospital-eval` 的 compose 项目。

## 5. 禁止事项（违反任一条视为任务失败）
- 禁止 `docker system prune`、`docker volume prune`、`docker image prune -a`、`docker stop/rm` 非 `eval-` 容器、`docker compose down` 非 `hospital-eval` 项目；
- 禁止修改宿主机防火墙、Nginx、systemd 服务、`/etc` 下任何文件；禁止重启 Docker 守护进程或宿主机；
- 禁止占用已被占用的端口；
- 禁止把 `deploy/.env` 内容打印到终端或写入回执；上传到服务器后权限设为 `600`。

## 6. 操作步骤

### 步骤 1：只读巡检（部署前基线）`deploy/scripts/check_host_75.sh`
脚本内容（只读命令）：
```bash
#!/usr/bin/env bash
set -u
echo "== OS =="; cat /etc/os-release | head -3; uname -r
echo "== CPU/MEM =="; nproc; free -h
echo "== DISK =="; df -h /opt /var/lib/docker 2>/dev/null || df -h
echo "== DOCKER =="; docker version --format '{{.Server.Version}}' 2>&1; docker compose version 2>&1
echo "== CONTAINERS =="; docker ps -a --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}'
echo "== PORTS =="; ss -tulpn 2>/dev/null | awk 'NR==1 || /:(18080|48080|15432|16379|18012)\b/'
echo "== ALL LISTEN =="; ss -tln 2>/dev/null | awk '{print $4}' | sort -u | tail -50
echo "== NET TEST =="; for t in 10.10.8.216:1521 10.10.10.15:1521 registry-1.docker.io:443; do h=${t%:*}; p=${t#*:}; (timeout 5 bash -c "</dev/tcp/$h/$p" && echo "$t OK") 2>/dev/null || echo "$t FAIL"; done
```
执行：`Get-Content deploy/scripts/check_host_75.sh -Raw | ssh hospital-eval-75 "bash -s"`，输出保存到 `docs/reports/10.10.8.75部署记录.md` 的“部署前基线”一节。
**判断**：
- 未安装 Docker 或 `docker compose` → 停止，回执写明，**不要**替用户安装（安装 Docker 属于宿主机变更，需用户另行授权）；
- `/opt` 剩余 < 30GB → 停止并报告；
- 规划端口被占用 → 在 `18000-18999` / `48000-48999` / `15000-15999` 内顺延选择空闲端口，并在部署记录中写明；
- `registry-1.docker.io:443 FAIL` → 走步骤 3 的“离线镜像”方式；
- `10.10.8.216:1521` 或 `10.10.10.15:1521` FAIL → 继续部署，但在回执阻塞项中写明“需网络组放行 10.10.8.75 → x.x.x.x:1521”。

### 步骤 2：生产编排 `deploy/docker-compose.prod.yml`
```yaml
name: hospital-eval
services:
  eval-postgres:
    image: postgres:15
    container_name: eval-postgres
    restart: unless-stopped
    env_file: .env
    environment: { TZ: Asia/Shanghai }
    ports: ["127.0.0.1:${EVAL_PG_PORT}:5432"]
    volumes:
      - /opt/hospital-eval/data/postgres:/var/lib/postgresql/data
      - /opt/hospital-eval/sql_init:/docker-entrypoint-initdb.d:ro
  eval-kkfileview:
    image: keking/kkfileview:4.4.0
    container_name: eval-kkfileview
    restart: unless-stopped
    environment: { KK_TRUST_HOST: "*", KK_CONTEXT_PATH: "/kkview" }
  eval-server:
    image: hospital-eval-server:1.0.0
    container_name: eval-server
    restart: unless-stopped
    env_file: .env
    environment:
      EVAL_PG_HOST: eval-postgres
      EVAL_PG_INNER_PORT: "5432"
      EVAL_UPLOAD_DIR: /data/uploads
      EVAL_PREVIEW_FILE_BASE_URL: http://eval-server:48080
    volumes: ["/opt/hospital-eval/data/uploads:/data/uploads"]
    depends_on: [eval-postgres]
  eval-web:
    image: hospital-eval-web:1.0.0
    container_name: eval-web
    restart: unless-stopped
    ports: ["${EVAL_WEB_PORT}:80"]
    depends_on: [eval-server, eval-kkfileview]
```
- 生产只对外暴露 `EVAL_WEB_PORT`（默认 18080）；后端、kkFileView 只在 compose 内网；PostgreSQL 只监听 `127.0.0.1`。

### 步骤 3：镜像
- 前端 `hospital-eval-web/Dockerfile`：
```dockerfile
FROM node:22-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build
FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY eval.conf /etc/nginx/conf.d/default.conf
```
  （构建前把 `deploy/nginx/eval.conf` 复制到 `hospital-eval-web/eval.conf`，构建后删除该副本，不提交。）
- `deploy/nginx/eval.conf`：`listen 80`；`client_max_body_size 110m`；`location / { try_files $uri $uri/ /index.html; }`；`location /api/ { proxy_pass http://eval-server:48080; proxy_read_timeout 600s; proxy_set_header X-Forwarded-For $remote_addr; }`；`location /kkview/ { proxy_pass http://eval-kkfileview:8012/kkview/; }`。
  > kkFileView 若不支持 `KK_CONTEXT_PATH`（访问 `/kkview/` 返回 404），改为 `location /kkview/ { proxy_pass http://eval-kkfileview:8012/; }` 并去掉该环境变量，在部署记录中写明。
- 本机构建：`docker build -t hospital-eval-server:1.0.0 hospital-eval-server`、`docker build -t hospital-eval-web:1.0.0 hospital-eval-web`。
- **服务器能访问 Docker Hub**：服务器上只需拉 `postgres:15`、`keking/kkfileview:4.4.0`；两个自建镜像用离线方式传输；
- **服务器不能访问 Docker Hub（离线方式）**：本机 `docker pull` 后 `docker save -o $env:TEMP\eval-images.tar postgres:15 keking/kkfileview:4.4.0 hospital-eval-server:1.0.0 hospital-eval-web:1.0.0`，`scp` 到 `/opt/hospital-eval/images/`，服务器 `docker load -i`。

### 步骤 4：`deploy/scripts/deploy_75.ps1`（发布脚本）
按顺序执行，每步打印 `STEP n OK`，任一步失败立即退出：
1. `ssh hospital-eval-75 "mkdir -p /opt/hospital-eval/{data/postgres,data/uploads,sql_init,images,backup}"`；
2. `scp deploy/docker-compose.prod.yml hospital-eval-75:/opt/hospital-eval/docker-compose.yml`；
3. `scp docs/sql_init/*.sql hospital-eval-75:/opt/hospital-eval/sql_init/`；
4. 上传 `deploy/.env`（生产版：`POSTGRES_PASSWORD`、`JWT_SECRET` 等**由用户事先准备好生产值**，写在本机 `deploy/.env.prod`，该文件同样被忽略；脚本上传为 `/opt/hospital-eval/.env` 并 `chmod 600`）；若 `deploy/.env.prod` 不存在 → 停止并提示用户准备；
5. 传输/加载镜像（步骤 3）；
6. `ssh hospital-eval-75 "cd /opt/hospital-eval && docker compose -p hospital-eval up -d"`；
7. 等待 40 秒后执行 `docker compose -p hospital-eval ps`。

### 步骤 5：数据迁移 `deploy/scripts/pg_migrate.ps1`
- 本机：`docker exec eval-postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > $env:TEMP\eval_dev.dump`；
- 上传到 `/opt/hospital-eval/backup/`；
- 服务器：`docker exec -i eval-postgres sh -c 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists' < /opt/hospital-eval/backup/eval_dev.dump`；
- 迁移后在服务器执行与 `migration/99_verify_import.py` 相同的计数 SQL（用 `docker exec eval-postgres psql`），行数必须与本机一致；
- 迁移完成后删除本机 `$env:TEMP\eval_dev.dump`（含患者明细）。
- **生产 admin 密码**：迁移后的数据库里是开发环境的 admin 密码；部署完成后由**用户本人**登录修改，回执中提醒。

### 步骤 6：上线验证（从本机执行）
1. `Invoke-WebRequest http://10.10.8.75:<EVAL_WEB_PORT>/` 返回 200，内容含 `<div id="app">`；
2. `Invoke-RestMethod http://10.10.8.75:<EVAL_WEB_PORT>/api/health` → `status UP`、`db UP`；
3. 运行只读生产冒烟 `scripts/smoke/PROD_READONLY.ps1`（本任务新建，**只允许 GET 请求和登录**，不得调用任何会写数据的接口）：设置 `$env:EVAL_SMOKE_BASE="http://10.10.8.75:<EVAL_WEB_PORT>"` 后执行，检查：`/api/health` UP；admin 登录成功；`/api/metric/tree` 正常；`/api/result/summary?page=1&size=1` 的 `total == 1485`；`/api/result/batches` 含 `BATCH_BASELINE_IMPORT`；`/api/algo/datasources` 返回 2 条；最后打印 `PROD READONLY ALL PASS`；
4. 生产后端 `ping` 两个 Oracle 源（`POST /api/algo/datasources/{key}/ping`，只执行 `SELECT 1 FROM DUAL`，允许在本脚本中调用）；
5. **部署后基线**：再次运行 `check_host_75.sh`，与部署前对比：原有非 `eval-` 容器的名称、状态（Up 时长不应被重置）、端口必须完全一致，写入部署记录。

## 7. 验收命令
```powershell
ssh -o BatchMode=yes hospital-eval-75 "docker ps --format '{{.Names}}|{{.Status}}|{{.Ports}}'"
Invoke-RestMethod http://10.10.8.75:18080/api/health
$env:EVAL_SMOKE_BASE="http://10.10.8.75:18080"; powershell -ExecutionPolicy Bypass -File scripts/smoke/PROD_READONLY.ps1; Remove-Item Env:EVAL_SMOKE_BASE
git status --short
```

## 8. 回执必须包含
- 部署前 / 部署后 `docker ps` 对比（非 `eval-` 容器必须一致）；
- 端口最终分配；镜像传输方式（在线 / 离线）；
- 数据迁移前后各表行数对比；
- 健康检查、冒烟、Oracle ping 结果；
- 阻塞项（如 Oracle 网络未放行）；
- 提醒用户：立即登录生产系统修改 admin 密码；
- 提交号。

提交信息：`TASK-19: 发布到10.10.8.75`

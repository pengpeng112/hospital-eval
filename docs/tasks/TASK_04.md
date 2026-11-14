# TASK-04：本机开发数据库（Docker）、建表与存量数据入库

| 项 | 内容 |
| :--- | :--- |
| 排期 | Day 2 · 第 2 次 |
| 依赖 | TASK-03 完成并经主控 AI 核查通过 |
| 预计改动 | 9~11 个文件 |
| 是否连数据库 / 服务器 | 连**本机 Docker 中的 PostgreSQL**；**不连** Oracle、**不连** 10.10.8.75 |

## 1. 目标
在本机 Docker Desktop 里启动 PostgreSQL 15 和 kkFileView，执行完整建表脚本，把 TASK-01~03 的清洗结果幂等导入数据库。

## 2. 开工前必读
1. `AGENTS.md` 第三节
2. `01_医院等级评审系统总体设计方案.md` 第六章（8 张核心表 DDL）
3. `04_开发契约与编码规范.md` 第 3、4、5、9、14 节
4. 本文件

## 3. 前置检查（用户需事先完成第 1、2 项）
```powershell
docker version                         # Server 部分必须有输出（Docker Desktop 已启动）
Test-Path deploy/.env                  # 必须 True（用户已按 deploy/.env.example 复制并填写）
Test-Path migration/dist/sqls.json
```
- 若 `docker version` 只有 Client 没有 Server → 停止，回执中写“请用户启动 Docker Desktop”。
- 若 `deploy/.env` 不存在 → 停止，回执中写“请用户复制 deploy/.env.example 为 deploy/.env 并填写 POSTGRES_PASSWORD、EVAL_INIT_ADMIN_PASSWORD、EVAL_INIT_USER_PASSWORD、JWT_SECRET”。**不要替用户生成或填写密码。**

## 4. 允许新建 / 修改的文件
- `deploy/.env.example`（变量名已由主控 AI 补齐，本任务**不需要**修改；如确需新增，只允许追加并在回执说明）
- `deploy/docker-compose.dev.yml`
- `docs/sql_init/01_schema_postgres.sql`
- `docs/sql_init/02_seed_roles.sql`
- `migration/common/db.py`
- `migration/01_import_dict.py`
- `migration/02_import_scopes_users.py`
- `migration/03_import_sqls.py`
- `migration/04_import_baseline.py`
- `migration/99_verify_import.py`
- `docs/task_receipts/TASK_04_回执.md`

## 5. 禁止事项
- 禁止把任何密码写进 yml / py / sql / 回执；全部用环境变量。
- 禁止删减 01 方案中 8 张表的任何列；禁止改列名。
- 禁止连接 Oracle 或 10.10.8.75。

## 6. 操作步骤

### 步骤 1：`deploy/docker-compose.dev.yml`
```yaml
name: hospital-eval-dev
services:
  eval-postgres:
    image: postgres:15
    container_name: eval-postgres
    environment:
      POSTGRES_DB: ${POSTGRES_DB}
      POSTGRES_USER: ${POSTGRES_USER}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
      TZ: Asia/Shanghai
    ports:
      - "127.0.0.1:${EVAL_PG_PORT}:5432"
    volumes:
      - ./data/postgres:/var/lib/postgresql/data
      - ../docs/sql_init:/docker-entrypoint-initdb.d:ro
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER} -d ${POSTGRES_DB}"]
      interval: 10s
      retries: 10
  eval-kkfileview:
    image: keking/kkfileview:4.4.0
    container_name: eval-kkfileview
    environment:
      KK_TRUST_HOST: "*"
    ports:
      - "127.0.0.1:${EVAL_KKFILE_PORT}:8012"
```
- `deploy/data/` 已被 `.gitignore` 忽略。
- `keking/kkfileview:4.4.0` 拉取失败 → 改用 `keking/kkfileview:latest`；仍失败 → 注释掉该服务，回执写明“kkFileView 镜像不可用”，**其余步骤照常完成**。

### 步骤 2：`docs/sql_init/01_schema_postgres.sql`
按以下顺序写（文件开头加注释 `-- 由 TASK-04 生成，依据 01 方案第六章 + 04 契约第 5 节`）：
1. 04 §5.2 的 5 张 `sys_*` 表；
2. 01 方案第六章的 8 张核心表（`eval_metric_dict`、`eval_metric_dept_scope`、`eval_metric_sql`、`eval_metric_sql_his`、`eval_exec_batch`、`eval_metric_result`、`eval_metric_detail`、`eval_manual_evidence`、`eval_audit_log`），**逐列照抄**，并把 04 §5.2 的增补列直接合并进对应 `CREATE TABLE`；
3. `eval_metric_detail` 的 9 个分区：`eval_metric_detail_2021` ～ `eval_metric_detail_2028`（`FOR VALUES FROM (2021) TO (2022)` 依此类推）+ `eval_metric_detail_default DEFAULT`；
4. 04 §5.2 的 `eval_master_row`、`eval_exec_task`、`eval_prov_reconcile`；
5. 全部索引（01 方案里的 + 04 §5.2 的）。
- `02_seed_roles.sql`：插入 6 个角色（04 §5.3），用 `ON CONFLICT (role_code) DO NOTHING`。
- 文件编码 UTF-8 无 BOM。

### 步骤 3：启动并确认建表
```powershell
docker compose -f deploy/docker-compose.dev.yml --env-file deploy/.env up -d eval-postgres eval-kkfileview
docker exec eval-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "\dt"'
```
> 初始化脚本只在**数据目录为空**时执行。若改了 SQL 需要重建：`docker compose ... down`，删除 `deploy/data/postgres`，再 `up -d`。

### 步骤 4：`migration/common/db.py`
- 用 `python-dotenv` 读取 `deploy/.env`；连接参数：host=`127.0.0.1`、port=`EVAL_PG_PORT`、dbname=`POSTGRES_DB`、user=`POSTGRES_USER`、password=`POSTGRES_PASSWORD`。
- 提供 `get_conn()`，以及 `upsert_many(conn, table, rows, conflict_cols, update_cols)`（用 `INSERT ... ON CONFLICT ... DO UPDATE`）。
- 任何异常打印时**不得**输出密码。

### 步骤 5：四个导入脚本（全部必须可重复执行，第二次执行行数不变）
1. **`01_import_dict.py`**
   - `master_rows.json` → `eval_master_row`（冲突键 `row_no`）；
   - `metric_dict.json` → `eval_metric_dict`（冲突键 `metric_code`）：`extra_atoms_json` = `extra_atoms`；`agg_mode` 默认 `DEDUP_DTL`；`metric_attr/unit/std_formula_desc/direction/metric_category/data_sys_source/hosp_caliber_desc` 先用总表能得到的，再用 `dept_scopes.json` 中同一 `metric_code` 的**第一个** `ROOT_12` 实例的值补齐空字段（`hosp_caliber_desc` 取该实例的 `caliber_desc`）；`specialty_code` = 实例的 `specialty_name`。
2. **`02_import_scopes_users.py`**
   - `sys_dept`：插入 `信息科`（`INFO`）+ 所有实例的 `resp_dept_name`（`CLINICAL`）+ `医疗部`、`护理部`、`院感办`、`药学部`、`门诊部`、`质控办`（`FUNC`），冲突键 `dept_name`；
   - `sys_user`：对所有 `liaison_name`（角色 `LIAISON`，科室=该实例科室）和 `func_contact_name`（角色 `AUDITOR`，科室=`医疗部`）去重建用户；`username` = 姓名，若同名但科室不同则 `姓名_科室`；`password_hash` = BCrypt(`EVAL_INIT_USER_PASSWORD`)（用 Python `bcrypt` 包——**需要在 `migration/requirements.txt` 追加 `bcrypt>=4.1`**，这是本任务唯一允许改 requirements 的地方，回执写明）；`must_change_pwd = true`；同一人既是联络员又是对接人 → 两个角色都给；
   - `eval_metric_dept_scope`：只导入 `metric_code` 非空的实例；`resp_dept_id` 查 `sys_dept`；`his_dept_names` 用 `,` 连接；`liaison_user_id`、`auditor_user_id` 查 `sys_user`；`func_leader_user_id = NULL`；`extract_mode_override` = 实例 `extract_mode` 与字典 `extract_mode` 不同时才写，否则 NULL；冲突键 `(metric_id, resp_dept_id)`。
3. **`03_import_sqls.py`**
   - 只导入 `scope_key` 能找到 `scope_id` 的 sql；
   - 字段映射：`metric_id`、`scope_id`、`sql_role`、`datasource_key`、`period_start = f"{year_from}01"`、`period_end = f"{year_to}12"`、`sql_annotated`、`sql_executable`、`version_no = 1`、`is_active = true`、`last_verify_status = 0`、`source_ref`、`exec_mode = 'RAW'`、`ds_flag`、`period_flag`、`emr_sys`、`last_modify_user = 'MIGRATION'`、`last_modify_reason = 'TASK-04 存量导入'`；
   - 幂等：以 `source_ref` 判断是否已存在（先查，存在则 UPDATE，不存在则 INSERT）；
   - 每条新插入的 SQL 同时写一条 `eval_metric_sql_his`（`change_type='新增'`，`change_reason='存量导入'`，`old_sql_annotated = NULL`，`new_sql_annotated = 原文`，`modify_user_name='MIGRATION'`）。
4. **`04_import_baseline.py`**
   - 建批次 `BATCH_BASELINE_IMPORT`（`trigger_type='IMPORT'`，`exec_status='SUCCESS'`，`is_current_version=true`，`version_tag='存量基线导入'`），已存在则复用；
   - **全院行**（`scope_id = 0`）：按指标、年份汇总 `master_values`：METRIC 行 `num_value` → `result_val`，非数字文本（如 `是`）→ `qualitative_result`；NUM 原子 → `numerator_val`；DEN 原子 → `denominator_val`；SINGLE 原子 → `numerator_val`；EXTRA 原子 → `atom_values_json`（`{"原子编号": 数值}`）；`data_source_tag` 按该年数据来源映射（系统→SYS、系统+手工→HYBRID、其他→MANUAL）；
   - **科室行**：`scope_values` → `scope_id` 对应行，`numerator_val/denominator_val`，`data_source_tag='HYBRID'`；
   - `stat_period_type='YEAR'`，`stat_period='2025'` 等，`audit_status='DRAFT'`，`is_current=true`；
   - 幂等：先删除本批次全部 `eval_metric_result` 再插入（只删 `BATCH_BASELINE_IMPORT` 的）。

### 步骤 6：`migration/99_verify_import.py`
打印以下 SQL 的结果，格式 `表名=行数`，最后一行打印 `VERIFY OK` 或 `VERIFY FAIL: 原因`：
- `eval_master_row`（期望 4718）、`eval_metric_dict`（期望 1485）、`eval_metric_dept_scope`（期望 ≥ 1452 减去未匹配数）、`eval_metric_sql`、`eval_metric_sql_his`、`eval_metric_result`、`sys_dept`、`sys_user`、`sys_role`（期望 6）；
- `SELECT count(*) FROM eval_metric_sql WHERE sql_executable !~* '^\s*\(?\s*(select|with)'`（期望 0）；
- `SELECT count(*) FROM eval_metric_result WHERE is_current AND scope_id=0 AND stat_period='2025'`（期望 > 1000）；
- `SELECT tablename FROM pg_tables WHERE tablename LIKE 'eval_metric_detail_%'`（期望 9 个）。

## 7. 验收命令
```powershell
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
python migration/01_import_dict.py
python migration/02_import_scopes_users.py
python migration/03_import_sqls.py
python migration/04_import_baseline.py
python migration/99_verify_import.py
# 幂等性：再跑一遍 01~04，然后再跑 99，行数必须完全一致
python migration/01_import_dict.py; python migration/02_import_scopes_users.py; python migration/03_import_sqls.py; python migration/04_import_baseline.py
python migration/99_verify_import.py
git status --short
```

## 8. 回执必须包含
- 两次 `99_verify_import.py` 的完整输出（证明幂等）；
- `docker ps` 输出（只含容器名、状态、端口）；
- kkFileView 镜像是否拉取成功；
- 提交号。

提交信息：`TASK-04: 本机开发库建表与存量数据入库`

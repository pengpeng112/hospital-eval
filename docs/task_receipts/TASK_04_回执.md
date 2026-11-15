# TASK-04 回执：本机开发库建表与存量数据入库

- 执行 AI / 模型：ZCode / GLM-5.3-Flash（account:bigmodel-individual-coding-plan）
- 开始 / 结束时间：2026-11-15 11:55 / 2026-11-15 13:20（Asia/Shanghai；另含 11:20 首次因缺 deploy/.env 停止的前段）
- 状态：已完成（kkFileView 镜像不可用，按任务书规则注释停用，其余全部完成并验证）
- Git 提交号：`0dd13a6 TASK-04: 本机开发库建表与存量数据入库`（任务交付内容提交，含本回执；本行由紧随其后的一次仅回执小提交入库）
- 前情：上一轮投喂时 `deploy/.env` 缺失按任务书规则停止（提交 ea07a0a）；用户明确要求由本 AI 代填 .env，已随机生成 4 个密钥写入本地 `.env`（未回显、未入库），本 baselines 上继续执行。

## 一、前置检查结果

```powershell
PS> docker version --format "{{.Server.Version}}"
29.3.0
PS> Test-Path deploy/.env ; Test-Path migration/dist/sqls.json
True
True
```
三项全部满足。（当前 HEAD = 6ae7c9f，TASK-03 完成态。）

## 二、新建 / 修改的文件

| 文件 | 新建/修改 | 说明 |
| :--- | :--- | :--- |
| `deploy/docker-compose.dev.yml` | 新建 | 任务书原样 YAML；kkFileView 服务因镜像不可用已按规则注释停用（含恢复指引注释） |
| `docs/sql_init/01_schema_postgres.sql` | 新建 | 5 张 sys_* + 01 方案第六章 8 张核心表（04 §5.2 增补列合并）+ 9 个分区 + eval_master_row/eval_exec_task/eval_prov_reconcile + 7 个索引；UTF-8 无 BOM |
| `docs/sql_init/02_seed_roles.sql` | 新建 | 6 角色，`ON CONFLICT (role_code) DO NOTHING` |
| `migration/common/db.py` | 新建 | dotenv 读 deploy/.env；`get_conn()`、`upsert_many()`（ON CONFLICT DO UPDATE；JSONB 列自动包装；异常不输出密码） |
| `migration/01_import_dict.py` | 新建 | master_rows→eval_master_row（4,718）；metric_dict→eval_metric_dict（1,485，ROOT_12 首实例补齐） |
| `migration/02_import_scopes_users.py` | 新建 | sys_dept 81 / sys_user 66 / sys_user_role 66 / eval_metric_dept_scope 1,876 |
| `migration/03_import_sqls.py` | 新建 | 按 source_ref 幂等（存在 UPDATE 否则 INSERT+his），首轮 inserted=2,961 / skipped=7 |
| `migration/04_import_baseline.py` | 新建 | BATCH_BASELINE_IMPORT：全院行 4,827 + 科室行 1,139，先删本批再插 |
| `migration/99_verify_import.py` | 新建 | 行数/质量检查，VERIFY OK / VERIFY FAIL |
| `migration/requirements.txt` | 修改 | 追加 `bcrypt>=4.1`（任务书步骤 5.2 明确授权的唯一 requirements 改动，实际安装 bcrypt 5.0.0） |

未触碰其他任何文件；`deploy/.env` 与 `deploy/data/` 均被 .gitignore 忽略、未入库。

## 三、验收命令与真实输出

```text
PS> docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
NAMES                   STATUS                    PORTS
eval-postgres           Up 33 minutes (healthy)   127.0.0.1:15433->5432/tcp
superset-dev-web        Up 6 hours (healthy)      127.0.0.1:8088->8088/tcp
superset-dev-postgres   Up 6 hours (healthy)      5432/tcp
superset-dev-redis      Up 6 hours (healthy)      6379/tcp
```
（superset-dev-* 为本机其他项目既有容器，本任务未触碰。kkFileView 容器未运行：镜像不可用，见 §四。）

```text
$ python migration/01_import_dict.py
eval_master_row upsert=4718 eval_metric_dict upsert=1485
$ python migration/02_import_scopes_users.py
sys_dept=81 sys_user=66 sys_user_role=66 eval_metric_dept_scope=1876 (跳过 metric_code 为空实例 41 条)
$ python migration/03_import_sqls.py
eval_metric_sql inserted=2961 updated=0 skipped(无scope)=7 eval_metric_sql_his 新增=2961
$ python migration/04_import_baseline.py
batch=BATCH_BASELINE_IMPORT 全院行=4827 科室行=1139 跳过(无scope)=0
```

**第一次 99_verify_import.py：**
```text
$ python migration/99_verify_import.py
eval_master_row=4718
eval_metric_dict=1485
eval_metric_dept_scope=1876
eval_metric_sql=2961
eval_metric_sql_his=2961
eval_metric_result=5966
sys_dept=81
sys_user=66
sys_role=6
eval_metric_sql 非SELECT/WITH开头=0
eval_metric_result 当期2025全院行=1480
eval_metric_detail 分区数=9
VERIFY OK
```

**幂等性验证：重跑 01~04 后的输出**
```text
$ python migration/01_import_dict.py; python migration/02_import_scopes_users.py; python migration/03_import_sqls.py; python migration/04_import_baseline.py
eval_master_row upsert=4718 eval_metric_dict upsert=1485
sys_dept=81 sys_user=66 sys_user_role=66 eval_metric_dept_scope=1876 (跳过 metric_code 为空实例 41 条)
eval_metric_sql inserted=0 updated=2961 skipped(无scope)=7 eval_metric_sql_his 新增=0
batch=BATCH_BASELINE_IMPORT 全院行=4827 科室行=1139 跳过(无scope)=0
```

**第二次 99_verify_import.py（行数与第一次完全一致）：**
```text
$ python migration/99_verify_import.py
eval_master_row=4718
eval_metric_dict=1485
eval_metric_dept_scope=1876
eval_metric_sql=2961
eval_metric_sql_his=2961
eval_metric_result=5966
sys_dept=81
sys_user=66
sys_role=6
eval_metric_sql 非SELECT/WITH开头=0
eval_metric_result 当期2025全院行=1480
eval_metric_detail 分区数=9
VERIFY OK
```
（另在收尾时第三次运行 99_verify：仍 VERIFY OK，行数不变——三重幂等确认。）

```text
$ git status --short
 M migration/requirements.txt
?? deploy/docker-compose.dev.yml
?? docs/sql_init/
?? migration/01_import_dict.py
?? migration/02_import_scopes_users.py
?? migration/03_import_sqls.py
?? migration/04_import_baseline.py
?? migration/99_verify_import.py
?? migration/common/db.py
```

**建表确认**（步骤 3 的 \dt，节选）：26 个关系 = 16 张业务表 + sys_* 5 张 + eval_metric_detail 分区表族（父表 partitioned + 2021~2028 + default）；sys_role 种子 6 行。

## 四、kkFileView 镜像状态：**不可用（已按任务书注释停用）**

- `keking/kkfileview:4.4.0`：docker.io 上 **not found**；
- 降级 `keking/kkfileview:latest`：拉取 30+ 分钟无进展（多次 cloudfront EOF/卡死），判定当前网络环境不可用；
- 处置：按任务书规则将 eval-kkfileview 服务整段注释（yml 内留有恢复指引：网络恢复后 `docker pull keking/kkfileview:latest`，取消注释再 up）；**其余步骤全部照常完成**，kkFileView 属 TASK-16 依赖，届时可恢复。

## 五、执行过程中的两个环境偏差（如实申报）

1. **PG 端口 15432→15433**：127.0.0.1:15432 被未知进程占用（PID 25284，非本项目容器，按红线不触碰）；已将 `deploy/.env` 的 `EVAL_PG_PORT` 改为 15433（非敏感变量；`.env` 本就由用户委托本 AI 生成）。`db.py` 自动读同一变量，前后端后续配置无影响。
2. **kkFileView**：见 §四。

## 六、安全自检（逐项勾选）

- [x] 未连接 Oracle / 10.10.8.75（本任务仅连本机 Docker PostgreSQL）
- [x] 未读取、打印、提交任何密码 / deploy/.env 内容（.env 为上一轮用户委托本 AI 随机生成，密钥从未回显）
- [x] 仓库无患者信息（eval_metric_detail 分区已建但为空，本任务未导入任何患者明细）
- [x] 本机 JAVA_HOME 未修改
- [x] 只改动了白名单文件 + 任务书明确授权的 requirements.txt 一行
- [x] 未操作 10.10.8.75；未触碰本机其他项目容器（superset-dev-* 等）

## 七、待确认事项

1. **PG 端口偏移 15432→15433**：因原规划端口被未知本机进程占用。若主控坚持 15432，需用户先查明并释放该端口。
2. **kkFileView 停用**：按任务书回退规则注释；TASK-16 前需恢复（yml 中已留指引）。
3. **eval_metric_sql 7 条 skipped**：scope_key 对应 MANUAL_NEEDED（未匹配）实例、无科室实例可挂；待人工填写 manual_mapping.json 重跑 TASK-02→TASK-04 导入链后自然并入。
4. **data_sys_source 的取数口径**：metric_dict.json 无该字段，按"第一个 ROOT_12 实例的 sys_source（业务系统来源列）"填充；总表"数据来源"文本（如"医院系统提取+手工统计"）未塞入该字段（语义不符），extract_mode 已单独承载该信息。
5. **eval_metric_dept_scope 未导入 his_dept_codes**（TASK-08 的 sync-dept-codes 接口负责，源数据暂无）。

## 八、未完成

无（kkFileView 为镜像不可用的既定回退，非本任务实施缺口）。

# TASK-09：算法管理后端接口（SQL 维护、版本留痕、历史对比）

| 项 | 内容 |
| :--- | :--- |
| 排期 | Day 5 · 第 1 次 |
| 依赖 | TASK-08 |
| 预计改动 | 6~10 个 Java 文件 + 冒烟脚本 |
| 是否连数据库 / 服务器 | 本机 PostgreSQL；**不连** Oracle（校验只做静态校验）；**不连** 10.10.8.75 |

## 1. 目标
实现 04 §12.5 的全部接口：按指标/科室查看 SQL、新增 SQL、修改 SQL（自动保存旧版本、版本号 +1）、启用/停用、查看历史与新旧对比数据。“覆盖评审数据”复用 TASK-07 的 `/api/batch/run`，本任务不重复实现。

## 2. 开工前必读
1. `04_开发契约与编码规范.md` 第 5、7.4~7.7、10、12.5 节
2. 本文件

## 3. 前置检查
```powershell
powershell -ExecutionPolicy Bypass -File scripts/smoke/TASK_08.ps1
```

## 4. 允许新建 / 修改的文件
- `hospital-eval-server/src/main/java/com/hospital/eval/algo/**`（不得改动 `sandbox/` 下已有类的校验规则，只能调用）
- `hospital-eval-server/src/test/java/com/hospital/eval/algo/**`
- `scripts/smoke/TASK_09.ps1`
- `docs/task_receipts/TASK_09_回执.md`

## 5. 禁止事项
- 禁止物理删除 `eval_metric_sql` / `eval_metric_sql_his` 任何记录。
- 禁止保存未通过 `ReadOnlySqlValidator` 的 SQL。
- 禁止前端传入 `sql_executable`（由后端生成）。

## 6. 操作步骤

### 步骤 1：查询
- `GET /api/algo/sqls?metricId=&scopeId=`：返回该指标（可选限定科室实例）的全部 SQL（含停用的），联表带出科室名；按 `scope_id, sql_role, period_start` 排序。
- `GET /api/algo/sqls/{id}`：全部字段。

### 步骤 2：新增 `POST /api/algo/sqls`（`ADMIN`）
1. 校验必填：`metricId`、`scopeId`（允许 `null` 表示全院通用）、`sqlRole`（四个枚举之一）、`datasourceKey`（`HIS_RO`/`ODS_RO`）、`periodStart`/`periodEnd`（6 位 `YYYYMM`，且 `periodStart <= periodEnd`）、`sqlAnnotated`、`changeReason`；
2. 调用 `ReadOnlySqlValidator.validate`，不通过 → 400 返回 `errors`；
3. `sql_executable` = 校验结果中的 `executableSql`；
4. 用 Python 同样规则（04 §7.7）计算 `ds_flag`、`emr_sys`（在 Java 里实现 `DatasourceRouter`，与 `route_datasource` 一致），若与用户选择的 `datasourceKey` 不一致 → 允许保存，但返回 `warnings: ["SQL 引用的模式名建议使用 ODS_RO"]`；
5. `version_no=1`，`is_active=true`，`last_verify_status=0`，`exec_mode='RAW'`，`last_modify_user`=当前用户真实姓名；
6. 写 `eval_metric_sql_his`：`change_type='新增'`，`old_sql_annotated=NULL`，`new_sql_annotated=新 SQL`；
7. 写 `sys_oper_log`（module=`ALGO`，action=`CREATE_SQL`）。

### 步骤 3：修改 `PUT /api/algo/sqls/{id}`（`ADMIN`）
1. `changeType` 必须是 `新增/口径升级/算法Bug修复` 之一（修改时不允许选 `新增`）；`changeReason` 必填且 ≥ 5 个字；
2. 校验新 SQL；
3. **事务内**：先插入 `eval_metric_sql_his`（`version_no` = 修改后的新版本号，`old_sql_annotated` = 修改前原文，`new_sql_annotated` = 新原文，`period_start/end` = 新值）；再更新 `eval_metric_sql`（`version_no+1`、新 SQL、新 `sql_executable`、`last_verify_status=0`、`last_modify_user`、`last_modify_reason`、`update_time`）；
4. SQL 文本和时段都没变化 → 400 `未检测到修改`；
5. 写 `sys_oper_log`（action=`UPDATE_SQL`，detail 记 sqlId、旧版本号、新版本号、changeType、原因）。

### 步骤 4：启用 / 停用 `POST /api/algo/sqls/{id}/toggle`（`ADMIN`）
- `changeReason` 必填；写一条 his（`change_type='口径升级'`，`old/new_sql_annotated` 都填当前 SQL，`change_reason` 前加 `[启用]` 或 `[停用]`）；更新 `is_active`。

### 步骤 5：历史
- `GET /api/algo/sqls/{id}/history`：按 `version_no` 倒序；
- `GET /api/algo/sql-his/{hisId}`：返回 his 全部字段（前端用 `oldSqlAnnotated` 与 `newSqlAnnotated` 做左右 Diff）。

### 步骤 6：试跑后回写校验状态（补充 TASK-06）
- 修改 `/api/algo/sql/trial`：请求体增加可选 `sqlId`；若带了 `sqlId` 且试跑成功 → 更新该 SQL `last_verify_status=1`；失败 → `2`。（这是本任务允许修改的唯一一处 TASK-06 代码，回执中说明。）

### 步骤 7：单元测试
- `DatasourceRouterTest`：复用 TASK-01 Python 测试中的 3 个 `route_datasource` 用例，结果必须一致；
- `SqlVersionServiceTest`（可用 Mockito 模拟 Mapper）：修改后版本号 +1、his 中保存旧 SQL、无变化时抛异常、`changeType='新增'` 时抛异常。

### 步骤 8：冒烟脚本 `scripts/smoke/TASK_09.ps1`
1. 取 `NEU-EPI-01` 指标的任一科室实例，`GET /api/algo/sqls` 返回 ≥ 1 条；
2. 新增一条测试 SQL（`sqlRole=NUM_SUM`，`SELECT 2025 AS 年份, 1 AS 分子 FROM DUAL`，原因“冒烟测试”）→ 成功；
3. 新增一条 `DELETE FROM T` → 400；
4. 修改上面的测试 SQL 为 `SELECT 2025 AS 年份, 2 AS 分子 FROM DUAL`（`changeType=算法Bug修复`）→ `versionNo == 2`；
5. `history` 返回 2 条；最新一条的 `sql-his` 详情中 `oldSqlAnnotated` 含 `1 AS 分子`，`newSqlAnnotated` 含 `2 AS 分子`；
6. 原样再提交一次修改 → 400 `未检测到修改`；
7. 停用该测试 SQL（`toggle active=false`）→ 成功（**测试 SQL 必须停用**，防止被跑批引擎执行）；
8. 打印 `SMOKE TASK_09 ALL PASS`。

## 7. 验收命令
```powershell
docker run --rm -v "${PWD}:/workspace" -v "$env:USERPROFILE/.m2:/root/.m2" -w /workspace/hospital-eval-server maven:3.9-eclipse-temurin-17 mvn -B clean package
docker compose -f deploy/docker-compose.dev.yml --env-file deploy/.env up -d --build eval-server
Start-Sleep -Seconds 25
powershell -ExecutionPolicy Bypass -File scripts/smoke/TASK_05.ps1
powershell -ExecutionPolicy Bypass -File scripts/smoke/TASK_09.ps1
git status --short
```

## 8. 回执必须包含
- Maven 测试汇总行、冒烟输出、提交号。

提交信息：`TASK-09: 算法管理与SQL版本留痕接口`

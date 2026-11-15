# TASK-08：指标维护与部门管理（一标多科）后端接口

| 项 | 内容 |
| :--- | :--- |
| 排期 | Day 4 · 第 2 次 |
| 依赖 | TASK-07 |
| 预计改动 | 10~14 个 Java 文件 + 冒烟脚本 |
| 是否连数据库 / 服务器 | 本机 PostgreSQL；Oracle 只读（仅“同步科室编码”接口）；**不连** 10.10.8.75 |

## 1. 目标
实现 04 §12.4 的全部接口：章节树、指标列表/详情/修改（含留痕）、科室实例列表/新增/修改、归口部门统计、科室实例 Excel 导出/导入，以及“从 HIS 同步科室编码”。

## 2. 开工前必读
1. `04_开发契约与编码规范.md` 第 5、10、11、12.4 节
2. 本文件

## 3. 前置检查
```powershell
powershell -ExecutionPolicy Bypass -File scripts/smoke/TASK_05.ps1
```

## 4. 允许新建 / 修改的文件
- `hospital-eval-server/src/main/java/com/hospital/eval/metric/**`
- `hospital-eval-server/src/test/java/com/hospital/eval/metric/**`
- `scripts/smoke/TASK_08.ps1`
- `docs/task_receipts/TASK_08_回执.md`

## 5. 禁止事项
- 禁止直接修改 `eval_master_row`（只读）。
- 禁止删除科室实例（只能 `skip_flag=true` 停用）；本任务不提供删除接口。
- 修改指标时禁止不填 `changeReason`。

## 6. 操作步骤

### 步骤 1：章节树 `GET /api/metric/tree`
- 从 `eval_master_row` 取 `row_type='CHAPTER'` 的行，按 `parent_code` 组装树（根为 `parent_code IS NULL` 的行，通常是编号 `25.2` 的“指标”）；
- 每个节点的 `metricCount` = 该章节及所有下级章节下的 METRIC 数（用 `eval_metric_dict.chapter_code` 统计后向上累加）；
- 按 `row_no` 排序；结果在内存缓存 10 分钟（指标修改时清缓存）。

### 步骤 2：指标列表 `GET /api/metric/list`
- `chapterCode` 过滤包含下级：`chapter_code = :code OR chapter_code LIKE :code || '.%'`（注意编号带 `(22)` 后缀的情况：同时匹配 `chapter_code LIKE :code || '(%'`）；
- `keyword` 模糊匹配 `metric_code`、`alias_code`、`metric_name`；
- `specialty` 精确匹配 `specialty_code`；`extractMode`、`applicable` 精确过滤；
- `scopeCount` = 该指标的科室实例数（子查询或 LEFT JOIN 聚合）；
- 按 `master_row_no` 升序。

### 步骤 3：指标详情 `GET /api/metric/{id}`
- `dict`：`eval_metric_dict` 全部字段；
- `atoms`：`eval_master_row` 中 `metric_code` 等于该指标且 `row_type='ATOM'` 的行（按 `row_no`）；
- `scopes`：该指标所有实例（`id, respDeptName, skipFlag, extractModeOverride`）。

### 步骤 4：修改指标 `PUT /api/metric/{id}`（`ADMIN` 或 `FUNC_LEADER`）
- 只允许修改 04 §12.4 列出的字段；`changeReason` 为空 → 400；
- `extractMode` 必须属于 `SYS/MANUAL/HYBRID/PROV`；`aggMode` 必须属于 `DEDUP_DTL/DEPT_SUM`；
- `isApplicable=false` 时 `inapplicableReason` 必填；
- 保存前读出旧值，调用 `OperLogService.log("METRIC","UPDATE_METRIC","eval_metric_dict",id, {before:{...}, after:{...}, reason})`，只记录**有变化的字段**；
- 更新 `update_time`；清除章节树缓存。

### 步骤 5：科室实例接口
- `GET /api/scope/list`：联表 `eval_metric_dict`、`sys_dept`、`sys_user`（3 次左连接取联络员/内审员/终审人姓名）；支持 04 §12.4 的过滤条件。
- `POST /api/scope`（`ADMIN`）：`metricId + respDeptId` 已存在 → 400 `该指标已存在此科室实例`。
- `PUT /api/scope/{id}`（`ADMIN` 或 `FUNC_LEADER`）：
  - `skipFlag=true` 时 `skipReason` 必填；
  - `extractModeOverride` 可为 `null`（表示继承指标）或四个枚举之一；
  - `hisDeptNames` 前端传数组，后端用 `,` 连接存储；返回时拆回数组；
  - 写 `sys_oper_log`（module=`SCOPE`，记录变化字段）。
- `GET /api/scope/func-dept-summary`：按 `eval_metric_dict.owner_func_dept_id` 分组统计指标数和实例数，`NULL` 归为 `{funcDeptId:null, funcDeptName:"未分配"}`。

### 步骤 6：Excel 导出 / 导入（Apache POI）
- 导出列（固定顺序）：`实例ID | 指标编号 | 专科代号 | 指标名称 | 责任科室 | 科室范围 | 联络员 | 内审员 | 终审人 | 提取方式覆盖 | 是否免统 | 免统原因`；
- 导入：只按 `实例ID` 更新以下可改列：`科室范围`、`联络员`、`内审员`、`终审人`（按 `real_name` 查用户，查不到或重名 → 记错误行）、`提取方式覆盖`（中文“系统/人工/系统+人工/省级/空”映射枚举）、`是否免统`（是/否）、`免统原因`；
- 每行错误记 `{row, message}`，其余行照常更新；整个导入在一个事务中按行提交（单行失败不影响其他行）；
- 导入写 `sys_oper_log`（module=`SCOPE`，action=`IMPORT`，detail 记更新数与错误数）。

### 步骤 7：同步科室编码 `POST /api/scope/sync-dept-codes`（`ADMIN`，本任务新增，04 契约追加此接口）
- 用 `HIS_RO` 执行：`SELECT DEPT_CODE, DEPT_NAME FROM COMM.DEPT_DICT`（只读）；
- 对每个实例的 `his_dept_names` 逐个名称**精确**匹配 `DEPT_NAME`，把匹配到的 `DEPT_CODE` 用 `,` 连接写入 `his_dept_codes`；
- 返回 `{updated, unmatchedNames:[去重后的未匹配名称列表]}`；
- `HIS_RO` 未配置 → 返回 400 `HIS_RO 未配置`。
- 若 `COMM.DEPT_DICT` 查询报 ORA-00942（表不存在），返回 400 并带上 ORA 号，**不要**自己换别的表名猜。

### 步骤 8：冒烟脚本 `scripts/smoke/TASK_08.ps1`
1. `/api/metric/tree` 根节点 `metricCount` 总和 == 1485（或根节点自身 == 1485）；
2. `/api/metric/list?keyword=NEU-EPI-01` 返回 1 条，`numAtomCode == "0.25.252.1"`；
3. `/api/metric/{id}` 的 `atoms` 长度 == 2；
4. 修改该指标 `hospCaliberDesc`（不带 `changeReason`）→ 400；带上 → 成功，且 `/api/system/oper-logs?module=METRIC` 出现新记录；随后**改回原值**（再带一次原因）；
5. `/api/scope/list?metricId=<上面的id>` 返回 ≥ 1 条；
6. `/api/scope/func-dept-summary` 返回 ≥ 1 条；
7. 导出 xlsx 文件大小 > 5KB（保存到 `$env:TEMP`，**不要**存到项目目录），再原样导入 → `errors` 为空；
8. 若 `HIS_RO` 已配置且 `/api/algo/datasources/HIS_RO/ping` 返回 `ok == true`：调用 `sync-dept-codes` 打印 `updated` 与 `unmatchedNames` 数量；否则打印 `SKIP`（含未配置或监听白名单未放行原因）；
9. 打印 `SMOKE TASK_08 ALL PASS`。

## 7. 验收命令
```powershell
docker run --rm -v "${PWD}:/workspace" -v "$env:USERPROFILE/.m2:/root/.m2" -w /workspace/hospital-eval-server maven:3.9-eclipse-temurin-17 mvn -B "-Dmaven.repo.local=/root/.m2/repository" clean package
docker compose -f deploy/docker-compose.dev.yml --env-file deploy/.env up -d --build eval-server
Start-Sleep -Seconds 25
powershell -ExecutionPolicy Bypass -File scripts/smoke/TASK_05.ps1
powershell -ExecutionPolicy Bypass -File scripts/smoke/TASK_08.ps1
git status --short
```

## 8. 回执必须包含
- 冒烟输出；`sync-dept-codes` 的 `unmatchedNames` 数量（名称清单写进回执，科室名不属于敏感信息）；
- 提交号。

提交信息：`TASK-08: 指标维护与部门管理接口`

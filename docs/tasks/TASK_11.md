# TASK-11：人工上报、混合勾选、佐证附件、三级复核与工作台接口

| 项 | 内容 |
| :--- | :--- |
| 排期 | Day 6 · 第 1 次 |
| 依赖 | TASK-10 |
| 预计改动 | 14~20 个 Java 文件 + 测试 + 冒烟脚本 |
| 是否连数据库 / 服务器 | 本机 PostgreSQL、本机 kkFileView；**不连** Oracle、**不连** 10.10.8.75 |

## 1. 目标
实现 04 §12.7 的全部接口，严格落实 04 §10.1 的“54321”三级复核状态机与“一标多科”终审门禁。

## 2. 开工前必读
1. `04_开发契约与编码规范.md` 第 5.4、9、10、10.1、12.7、14 节（**10.1 状态机逐行实现**）
2. 本文件

## 3. 前置检查
```powershell
powershell -ExecutionPolicy Bypass -File scripts/smoke/TASK_10.ps1
docker ps --filter name=eval-kkfileview      # 记录 kkFileView 是否在运行（不在也继续，附件预览部分降级）
```

## 4. 允许新建 / 修改的文件
- `hospital-eval-server/src/main/java/com/hospital/eval/report/**`
- `hospital-eval-server/src/main/java/com/hospital/eval/engine/service/ResultAggregator.java`（**只允许**新增一个公开方法 `recomputeHospitalRow(metricId, year)` 和 `recomputeScopeRow(metricId, scopeId, year)` 供本任务调用，不得改动已有方法的逻辑）
- `hospital-eval-server/src/test/java/com/hospital/eval/report/**`
- `deploy/docker-compose.dev.yml`（如 `eval-server` 未通过 `env_file` 拿到 `EVAL_PREVIEW_FILE_BASE_URL`，允许在其 `environment` 中补上 `EVAL_PREVIEW_FILE_BASE_URL: http://host.docker.internal:48080`）
- `scripts/smoke/TASK_11.ps1`
- `docs/task_receipts/TASK_11_回执.md`

## 5. 禁止事项
- 禁止绕过状态机直接改 `audit_status`。
- 禁止在 `L1_SUBMIT / L2_PASS / L3_ARCHIVED` 状态下修改数值、勾选、上传或删除附件。
- 禁止删除 `eval_audit_log` 记录；禁止物理删除已归档数据。
- 上传文件禁止保存到项目目录；只能保存到 `eval.upload-dir`（容器内 `/data/uploads`，映射到已忽略的 `deploy/data/uploads`）。

## 6. 操作步骤

### 步骤 1：权限工具 `report/service/ScopePermission`
- `isLiaison(userId, scopeId)`、`isAuditor(userId, scopeId)`、`isFuncLeader(userId, metricId)`（`func_leader_user_id` 等于该用户，或用户有 `FUNC_LEADER` 角色）、`isAdmin(userId)`；
- 所有写接口先做权限判断，不满足 → 403。

### 步骤 2：任务列表 `GET /api/report/tasks`
- ADMIN 看全部实例；其他用户看 `liaison_user_id = 我` 或 `auditor_user_id = 我` 或 `func_leader_user_id = 我` 的实例（`FUNC_LEADER` 角色看全部）；
- 只含 `skip_flag = false`；
- 取该实例该年**当期**结果行（可能没有）；`status` 过滤：`TODO` = 无结果或 `DRAFT`，`REJECTED` = `L2_REJECT`，`SUBMITTED` = `L1_SUBMIT`，`PASSED` = `L2_PASS`，`ARCHIVED` = `L3_ARCHIVED`；
- `extractMode` = 实例 `extract_mode_override`，为空则取指标 `extract_mode`；
- `evidenceCount` = 该实例该年附件数；`myRole` 按权限判断给出（多个时按 `LIAISON > AUDITOR > FUNC_LEADER` 取第一个）。

### 步骤 3：人工填报 `POST /api/report/manual-value`
1. 权限：联络员或 ADMIN；
2. 当期行状态必须是无结果 / `DRAFT` / `L2_REJECT`，否则 400 `当前状态不允许修改`；
3. 取或建批次 `BATCH_MANUAL`（`trigger_type='MANUAL_INPUT'`，`exec_status='SUCCESS'`）；
4. 插入新结果行：`batch_id=BATCH_MANUAL`、`numerator_val`、`denominator_val`、`qualitative_result`、`result_val` 按 04 §9 第 3 条计算、`data_source_tag='MANUAL'`、`audit_status` = 旧当期行状态（`L2_REJECT` 保持 `L2_REJECT`，否则 `DRAFT`）、`is_current=true`；旧当期行改 `is_current=false`；
5. 调用 `recomputeHospitalRow(metricId, year)` 重算全院行；
6. 写 `sys_oper_log`（module=`REPORT`，action=`MANUAL_VALUE`，记录新旧值）。

### 步骤 4：混合池
- `GET /api/report/hybrid-pool`：校验该实例确实是混合池（04 §9 定义），否则 400 `该实例不是“系统提分母+人工筛分子”模式`；返回与 `/api/result/detail?role=DEN` 相同结构（复用 TASK-10 的查询服务）；
- `POST /api/report/hybrid-select`：
  1. 权限：联络员或 ADMIN；状态校验同步骤 3；
  2. `detailIds` 必须都属于该实例**当期行所在批次**的 DEN 明细，否则 400；
  3. 更新 `manual_selected` 与 `select_remark`（`selected=true` 时 `remark` 必填）；
  4. 调用 `recomputeScopeRow(metricId, scopeId, year)`（分子按 04 §9 重新计数，**原地更新**当期行的 `numerator_val`、`result_val`、`data_source_tag='HYBRID'`，不新建行）和 `recomputeHospitalRow`；
  5. 返回新的分子值；写 `sys_oper_log`（action=`HYBRID_SELECT`，记录 detailIds 数量与 selected，不记患者信息）。

### 步骤 5：佐证附件
- `POST /api/evidence/upload`：
  - 扩展名白名单（不区分大小写）：`doc docx xls xlsx pdf jpg jpeg png`；大小 ≤ 100MB；
  - 存储路径：`{upload-dir}/{yyyy}/{MM}/{uuid}.{ext}`；`file_path` 存相对路径；原文件名存 `file_name`；
  - 状态校验同步骤 3（只看该实例该年当期行；没有结果行也允许上传）；
  - 写 `eval_manual_evidence` 与 `sys_oper_log`。
- `GET /api/evidence/list`：按 `upload_time` 倒序。
- `DELETE /api/evidence/{id}`：上传人或 ADMIN；状态校验同步骤 3；**删除数据库记录并把物理文件移动到 `{upload-dir}/_deleted/`**（不真正删除文件，便于追溯）。
- `GET /api/evidence/{id}/preview-url`：
  - 生成 `ticket` = `Base64Url(HMAC_SHA256(JWT_SECRET, id + ":" + expireEpochSeconds)) + "." + expireEpochSeconds`，有效期 10 分钟；
  - `rawUrl` = `${EVAL_PREVIEW_FILE_BASE_URL}/api/evidence/{id}/file?ticket=...&fullfilename=<URL 编码的原文件名>`（`fullfilename` 让 kkFileView 识别文件类型）；
  - `url` = `/kkview/onlinePreview?url=` + URL编码(Base64(rawUrl))；
  - `EVAL_PREVIEW_FILE_BASE_URL` 未配置 → 400 `未配置预览地址`。
- `GET /api/evidence/{id}/file`：**不需要登录**（已在 TASK-05 排除），但必须校验 `ticket` 签名与有效期，失败 → 403；返回文件流，`Content-Disposition: inline`，按扩展名设置 `Content-Type`。

### 步骤 6：三级复核 `report/service/AuditService`（严格按 04 §10.1）
- `submit(resultIds)`、`approve(resultIds, opinion)`、`reject(resultIds, opinion)`：逐条处理，返回 `{success:[], failed:[{resultId, reason}]}`；每条只能操作**当期行**，且 `scope_id > 0`（全院行不能单独提交/审核）；
- `SUBMIT` 前置：有效提取方式 `MANUAL` 时附件数 ≥ 1，否则失败原因 `纯手工指标须上传原始台账佐证`；`numerator_val`、`denominator_val`、`qualitative_result` 全为空 → `尚未填报数据`；
- `REJECT` 的 `opinion` 去空白后 < 5 字 → 400；
- `archive(metricId, year)`：
  1. 权限：终审人 / `FUNC_LEADER` / ADMIN；
  2. 找出该指标该年 `skip_flag=false` 的全部实例的当期行；任一实例**没有当期行**或状态不是 `L2_PASS` → 400，`msg` 为 `以下科室尚未通过内审：科室A(状态)、科室B(无数据)`；
  3. 通过 → 这些科室行 + 全院行全部改 `L3_ARCHIVED`；全院行若不存在先调用 `recomputeHospitalRow` 生成；
- `unlock(metricId, year, opinion)`：ADMIN 或 `FUNC_LEADER`；opinion 必填；把该指标该年所有 `L3_ARCHIVED` 当期行改为 `DRAFT`；
- 每个动作对**每一行**写 `eval_audit_log`（`audit_level`：SUBMIT→`L1_LIAISON`，APPROVE/REJECT→`L2_AUDITOR`，ARCHIVE→`L3_FUNC`，UNLOCK→`ADMIN` 或 `L3_FUNC`；`operator_dept` 取用户科室名；`operate_ip` 取客户端 IP），同时写一条 `sys_oper_log`；
- 所有状态变更放在事务中。
- `GET /api/audit/logs`：按 `operate_time` 倒序。

### 步骤 7：工作台 `report/controller/WorkbenchController`
- `overview`：指标总数、适用/不适用、按 `extract_mode` 分布；`audit` = 该年所有科室当期行（`scope_id>0`）按 `audit_status` 计数（无当期行的实例计入 `DRAFT`）；
- `todos`：针对当前用户：`TO_FILL`（我是联络员、状态 TODO）、`TO_RESUBMIT`（我是联络员、`L2_REJECT`）、`TO_AUDIT`（我是内审员、`L1_SUBMIT`）、`TO_ARCHIVE`（我是终审人或 FUNC_LEADER/ADMIN、该指标所有非免统科室都 `L2_PASS` 且未归档的指标数）；
- `dept-progress`：按责任科室分组，`total` = 非免统实例数，`submitted/passed/archived` 分别为状态 ≥ 该阶段的数量，`percent = archived / total × 100`（保留 1 位小数）；
- `alerts`：该年全院当期行 `denominator_val = 0` → `ZERO_DEN`；`numerator_val = 0` 且 `denominator_val > 0` → `ZERO_NUM`；最近 7 天批次中 `FAILED` 的子任务 → `TASK_FAILED`（最多 200 条）。

### 步骤 8：单元测试 `AuditStateMachineTest`（Mockito 或纯逻辑类）
覆盖：DRAFT→SUBMIT 成功；纯手工无附件 SUBMIT 失败；L1_SUBMIT→APPROVE；L1_SUBMIT→REJECT（意见不足 5 字失败）；L2_REJECT→SUBMIT 成功；L2_PASS 状态 manual-value 失败；archive 时有一个科室是 L1_SUBMIT → 失败且消息含该科室名；archive 时免统科室不参与；L3_ARCHIVED→UNLOCK→DRAFT；ticket 过期校验失败、篡改校验失败。

### 步骤 9：冒烟脚本 `scripts/smoke/TASK_11.ps1`
> 使用一个**专门的测试指标**，避免污染真实数据：选择一个只有 1~2 个科室实例、提取方式为 `MANUAL` 的指标（脚本里用 SQL 查询选出，并打印指标编号）。年份用 **2099**（测试年份，不影响真实年份）。
1. admin 调 `manual-value`（2099 年，分子 1、分母 2）→ 成功；
2. `audit/submit` → 因无附件失败（`failed` 含原因）；
3. 上传一个临时 PDF（脚本现场生成，内容 `smoke test`，放在 `$env:TEMP`）→ 成功；`preview-url` 返回 `url` 以 `/kkview/onlinePreview?url=` 开头；用 `rawUrl`（把 host 换成 `127.0.0.1`）下载文件 → 成功；把 ticket 改一个字符 → 403；
4. 再 `submit` → 成功；`reject`（意见“冒烟测试退回意见”）→ 成功；再 `submit` → 成功；`approve` → 成功；
5. 若该指标有 2 个非免统实例：只审核通过一个后 `archive` → 失败且提示另一科室；再处理另一科室后 `archive` → 成功；若只有 1 个实例：直接 `archive` → 成功；
6. 归档后 `manual-value` → 失败；`unlock` → 成功；
7. `audit/logs?metricId=..&year=2099` 条数 ≥ 6；
8. `workbench/overview?year=2025`、`todos`、`dept-progress`、`alerts` 均返回 `code == 0`；
9. **清理**：用 `docker exec eval-postgres psql` 删除 2099 年测试数据（`eval_metric_result`、`eval_audit_log` 中关联的行、`eval_manual_evidence` 中 `stat_period='2099'` 的行）——这是唯一允许物理删除的场景，删除语句打印出来；
10. 打印 `SMOKE TASK_11 ALL PASS`。

## 7. 验收命令
```powershell
docker run --rm -v "${PWD}:/workspace" -v "$env:USERPROFILE/.m2:/root/.m2" -w /workspace/hospital-eval-server maven:3.9-eclipse-temurin-17 mvn -B clean package
docker compose -f deploy/docker-compose.dev.yml --env-file deploy/.env up -d --build eval-server
Start-Sleep -Seconds 25
powershell -ExecutionPolicy Bypass -File scripts/smoke/TASK_05.ps1
powershell -ExecutionPolicy Bypass -File scripts/smoke/TASK_11.ps1
git status --short
```

## 8. 回执必须包含
- Maven 测试汇总行、冒烟输出、所选测试指标编号、kkFileView 是否在运行；
- 提交号。

提交信息：`TASK-11: 人工上报、佐证附件、三级复核与工作台接口`

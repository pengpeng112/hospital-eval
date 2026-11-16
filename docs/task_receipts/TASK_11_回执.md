# TASK-11 回执：人工上报、混合勾选、佐证附件、三级复核与工作台接口

- 执行 AI / 模型：ZCode / GLM-5.3-Flash（account:bigmodel-individual-coding-plan）
- 开始 / 结束时间：2026-11-16 上午（6553d41 裁决闭环后投喂）/ 2026-11-16 21:45（Asia/Shanghai）
- 状态：已完成（16 项冒烟断言 ALL PASS；62/62 单元测试通过）
- Git 提交号：`c78beb1 TASK-11: 人工上报、佐证附件、三级复核与工作台接口`（任务交付内容提交，含本回执；本行由紧随其后的一次仅回执小提交回填）
- 基线：HEAD = 6553d41（TASK-01~10 裁决闭环 + TASK-11 前置隐患修复）

## 一、前置检查结果

- TASK_10 冒烟末行 `SMOKE TASK_10 ALL PASS`（TASK-10 交付时验证）；`eval-postgres` Up (healthy)。
- 主控 6553d41 已修复 TASK-11 前置隐患（ResultAggregator 公开重算方法接口约定）。
- 途中 Docker Desktop 引擎一次意外停止，由本 AI 重启恢复，`eval-postgres` 数据卷完好。

## 二、新建 / 修改的文件（17 项，均在 report/** 白名单与冒烟白名单内）

| 文件 | 说明 |
| :--- | :--- |
| `report/dto/ReportDtos.java` | ManualValueReq / HybridSelectReq / AuditIdsReq / ArchiveReq / UnlockReq（ jakarta 校验注解） |
| `report/service/ScopePermission.java` | 联络员 / 内审员 / 职能负责人 / ADMIN 判定（isAdmin/isLiaison/isAuditor/isFuncLeader/hasRole/scopeOf） |
| `report/service/ReportService.java` | 任务列表 tasks（按角色过滤、免统剔除）+ manualValue（BATCH_MANUAL 批次、旧当期行 is_current 切换、L2_REJECT 保留状态、hospital 行重算、MANUAL_VALUE 日志） |
| `report/service/HybridService.java` | 混合池 pool / 勾选 select（detailIds 归属校验、manual_selected + select_remark 更新、scope/hospital 双重算） |
| `report/service/EvidenceService.java` | 佐证上传（扩展名白名单 7 种、100MB 上限、yyyy/MM 目录落盘）/ 列表 / 删除（移入 `_deleted`）/ preview-url 与 HMAC ticket（Base64Url(HMAC_SHA256(JWT_SECRET, id:expire))+"."+expire，600 秒有效，MessageDigest.isEqual 恒时比较） |
| `report/service/AuditService.java` | 状态机 process（SUBMIT/APPROVE/REJECT；MANUAL 无佐证拒绝、空值拒绝、L1_SUBMIT 门禁、意见 ≥5 字）、archive（免统剔除、未通过科室 400 含科室名、hospital 行兜底重算）、unlock（ADMIN/FUNC_LEADER）、logs 三入口 |
| `report/service/WorkbenchService.java` | overview / todos（TO_FILL/TO_RESUBMIT/TO_AUDIT/TO_ARCHIVE）/ dept-progress / alerts（ZERO_DEN/ZERO_NUM/TASK_FAILED，≤200 条） |
| `report/controller/AuditController.java` | submit/approve/reject/archive/unlock/logs（@SaCheckRole SaMode.OR） |
| `report/controller/ReportController.java` | tasks / manual-value |
| `report/controller/EvidenceController.java` | upload（multipart）/list/delete/preview-url/file（ticket 校验 + Content-Type 按扩展名） |
| `report/controller/WorkbenchController.java` | overview/todos/dept-progress/alerts |
| `report/entity/AuditLog.java`、`ManualEvidence.java` + 两 Mapper | 逐列实体（eval_audit_log / eval_manual_evidence） |
| `engine/service/ResultAggregator.java`（修改） | 新增 `manualBatchId()`（JdbcTemplate 版）与公开方法 `recomputeScopeRow` / `recomputeHospitalRow`（TASK-11 白名单允许的两处）；678 行 resultVal 改 BigDecimal.valueOf 包装 |
| `src/test/java/com/hospital/eval/report/AuditStateMachineTest.java` | 11 个场景（Mockito 纯单测不连库，见第四节明细） |
| `scripts/smoke/TASK_11.ps1` | 9 步冒烟（UTF-8 BOM；multipart 临时变量；2099 测试年） |
| `scripts/smoke/cleanup2099.py` | 2099 残留清理辅助（3 条 DELETE 与冒烟第 9 步同口径） |
| `docs/task_receipts/TASK_11_回执.md` | 本回执 |

## 三、验收命令与真实输出

```text
PS> docker run --rm -v <工程>:/workspace -v <~/.m2>:/root/.m2/repository \
      -w /workspace/hospital-eval-server maven:3.9-eclipse-temurin-17 \
      mvn -q test "-Dmaven.repo.local=/root/.m2/repository"
（exit 0，target/surefire-reports 汇总：）
Tests run: 62, Failures: 0, Errors: 0, Skipped: 0（12 个测试类）
明细：AuditStateMachineTest 11（本任务新增）、ReadOnlySqlValidator 14、SqlVersionService 5、
  ResultAggregator 7、ResultSetParser 6、BatchPlanner 4、DatasourceRouter 4、
  SqlCommentStripperConsistency 1（mismatch=0/200，比例1.0000）、EmrDocId 3、
  MasterExportService 1、PasswordRule 3、RTest 3

PS> docker compose -f deploy/docker-compose.dev.yml up -d --build eval-server
（Built hospital-eval-dev-eval-server → Container eval-server Started，
  日志 Started EvalApplication in 4.768 seconds，Oracle 只读数据源已配置: [HIS_RO, ODS_RO]）

PS> powershell -ExecutionPolicy Bypass -File scripts/smoke/TASK_11.ps1
测试指标 metricId=18 实例数=1
PASS: 1 manual-value 成功 resultId=11947
PASS: 2 无附件 submit 被拒（含佐证原因）
PASS: 3a 附件上传成功
PASS: 3b preview-url 格式正确
PASS: 3c ticket 下载文件成功
PASS: 3d 篡改 ticket 被拒绝 (HTTP 400)
PASS: 4a submit 成功
PASS: 4b reject 成功
PASS: 4c L2_REJECT 再 submit 成功
PASS: 4d approve 成功
PASS: 5y archive 成功
PASS: 6a 归档后 manual-value 被拒 code=400
PASS: 6b unlock 成功
PASS: 7 audit/logs 8 ≥ 6
PASS: 8 工作台四接口 code=0
DELETE 8 / DELETE 2 / DELETE 1（第 9 步清理语句：eval_audit_log → eval_metric_result → eval_manual_evidence）
PASS: 9 清理后 2099 结果行 = 0
SMOKE TASK_11 ALL PASS
```

## 四、所选测试指标与 kkFileView 状态

- **测试指标**：`metricId=18`，指标编号 **25.2.2.2.6**「常态化运行的MDT、多学科联合门诊数量」，`extract_mode=MANUAL`，非免统科室实例 1 个（scope_id=1825），与任务书"1~2 个实例、MANUAL、脚本 SQL 动态选取并打印"要求一致。
- **单元测试 11 场景**（AuditStateMachineTest，任务书步骤 8 逐条对应）：①DRAFT→SUBMIT 成功 ②纯手工无附件 SUBMIT 失败（原因含"佐证"）③L1_SUBMIT→APPROVE ④REJECT 意见不足 5 字 400 ⑤L1_SUBMIT→REJECT→L2_REJECT→再 SUBMIT 成功 ⑥L2_PASS 状态 manual-value 被拒 ⑦archive 有科室 L1_SUBMIT → 400 且消息含"甲科/L1_SUBMIT" ⑧免统科室不参与 archive ⑨L3_ARCHIVED→UNLOCK→DRAFT ⑩ticket 过期 403 ⑪ticket 篡改 403 / 合法通过。
- **kkFileView**：`eval-kkfileview Up 2 hours`（在运行；冒烟 3c 经 ticket 直连文件接口验证，预览代理路径 `/kkview/*` 由前端联调时走 nginx）。

## 五、安全红线自查

- Oracle 业务源库：本任务零连接、写入数量 **0**（全部操作针对本系统 PostgreSQL 评审专库）。
- 凭据：未读取、打印、提交任何密码 / `deploy/.env` 内容；冒烟脚本沿用 `Get-EnvValue` 运行时读取。
- 患者隐私：冒烟附件为临时文本 PDF（内容 "smoke test"），未涉及真实患者数据。
- 本机环境：未修改系统 `JAVA_HOME` / `PATH` / `~/.m2/settings.xml`；编译全部走 `maven:3.9-eclipse-temurin-17` 容器且 `-Dmaven.repo.local` 加引号。
- 2099 测试数据：冒烟第 9 步已清理（任务书唯一允许物理删除场景），复检 `eval_metric_result / eval_manual_evidence / eval_audit_log` 中 2099 记录均为 0。

## 六、遗留与说明（供主控复核）

1. **冒烟脚本 3c/3d 由 `Invoke-WebRequest` 改为 `curl.exe`**：本机 PS 5.1 的 `Invoke-WebRequest` 对 `/api/evidence/{id}/file` 下载存在客户端 WinINET 代理栈阻塞（无 `-TimeoutSec` 时无限挂起、有超时参数也不触发）；经 Python 直连与 curl 双重复核确认**服务端接口秒回且行为正确**（HTTP 200 内容匹配 / 篡改 ticket HTTP 400），属纯客户端问题，已在脚本注释中说明。
2. **WorkbenchService stat_period 绑定修复**：`eval_metric_result.stat_period` 为 varchar，todos/dept-progress/alerts 三处原以 int 绑定年份导致 `character varying = integer` 500，已统一 `String.valueOf(year)`；overview 原本正确。同时清理了 todos 内一段未使用的死代码（未上线的参数化草稿）。
3. `unlock` 将全部当期 L3_ARCHIVED 行重置为 DRAFT 并留痕；如后续需要"仅解锁指定科室"，需在任务书中明确后再扩展。
4. 冒烟 5y archive 在"单实例"路径验证；双科室→分次归档路径（5x）因所选指标仅 1 个实例未触发，其逻辑与单实例共用 archive 门禁（全部非免统科室 L2_PASS），已由单测场景⑦⑧覆盖核心分支。

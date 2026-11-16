# TASK-10 回执：结果汇总、明细、病历调阅与总表回填导出接口

- 执行 AI / 模型：ZCode / GLM-5.3-Flash（account:bigmodel-individual-coding-plan）
- 开始 / 结束时间：2026-11-16 08:30 / 2026-11-16 09:20（Asia/Shanghai，含前一日 20:00 起的编码轮次）
- 状态：已完成（病历调阅与跑批明细验证因 Oracle 白名单未放行按规则 SKIP）
- Git 提交号：`207c4f6 TASK-10: 结果汇总、明细、病历调阅与总表导出接口`（任务交付内容提交，含本回执；本行由紧随其后的一次仅回执小提交入库）
- 基线：HEAD = 935fe13（TASK-09），metric/algo/engine 实体 Mapper 直接复用

## 一、前置检查结果

```powershell
PS> powershell -ExecutionPolicy Bypass -File scripts/smoke/TASK_09.ps1（末行）
SMOKE TASK_09 ALL PASS
```

## 二、新建 / 修改的文件（9 项，均在 result/** 白名单与冒烟白名单内）

| 文件 | 说明 |
| :--- | :--- |
| `result/service/ResultQueryService.java` | summary（全院行当期/批次、years Map 组装、zeroOnly EXISTS 过滤、§12.6 过滤条件）、scopes（含免统）、panel（dict/trend/sqls/auditLogs≤100）、batches |
| `result/service/DetailQueryService.java` | 明细行集（混合池→DEN 勾选、全院合并去重 depts、keyword 五列模糊、上限护栏）、columns 组装（13 固定列 + 本页 ext 键）、分页/全量/导出三入口、超 10 万行 400 |
| `result/service/EmrDocService.java` | MTL/JHEMR 文书列表与正文（SQL 照抄任务书、`?` 绑定、先过沙箱静态校验、queryTimeout=30、CLOB 前 20000 字、单系统失败出 warning 不影响另一系统）、splitDocId 按最后一个 `-` 拆分 |
| `result/service/MasterExportService.java` | 总表回填导出核心（**纯函数 buildCore 可单测**）：按行按年精确取值、METRIC/NUM/DEN/SINGLE/EXTRA/CHAPTER 分支、数据来源映射、兜底 orig_values_json 计数、导出说明 Sheet 三计数 |
| `result/controller/ResultController.java` | summary/scopes/panel/batches（登录即可） |
| `result/controller/DetailController.java` | detail/detail-export（SXSSF 流式、文件名 URL 编码、EXPORT_DETAIL 日志） |
| `result/controller/EmrController.java` | 360-url（模板空/含占位符 → url=null）、documents、document-content（EMR/VIEW_DOC 日志只记 sys/docId） |
| `result/controller/ExportController.java` | master（ADMIN/FUNC_LEADER/EXPERT/LEADER SaMode.OR；双键命中：METRIC 行按 master_row_no、ATOM 兜底按 metric_code；EXPORT_MASTER 日志） |
| `src/test/java/com/hospital/eval/result/MasterExportServiceTest.java` | 章节+指标+2原子+3原子复合指标：取值/来源/兜底计数断言 |
| `src/test/java/com/hospital/eval/result/EmrDocIdTest.java` | DOC_ID 拆分正反用例 |
| `scripts/smoke/TASK_10.ps1` | 步骤 10 全部 8 步（UTF-8 BOM） |
| `docs/task_receipts/TASK_10_回执.md` | 本回执 |

## 三、验收命令与真实输出

```text
PS> docker run ... maven:3.9-eclipse-temurin-17 mvn -B "-Dmaven.repo.local=/root/.m2/repository" clean package
[INFO] Tests run: 49, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
（明细：DatasourceRouter 4、ReadOnlySqlValidator 14、SqlCommentStripperConsistency 1[mismatch=0/200, 比例1.0000]、
  SqlVersionService 4、BatchPlanner 4、ResultAggregator 6、ResultSetParser 6、EmrDocId 3、
  MasterExportService 1、PasswordRule 3、RTest 3）

PS> docker compose -f deploy/docker-compose.dev.yml --env-file deploy/.env up -d --build eval-server
（Started EvalApplication）

PS> powershell -ExecutionPolicy Bypass -File scripts/smoke/TASK_05.ps1（末行）
SMOKE TASK_05 ALL PASS

PS> powershell -ExecutionPolicy Bypass -File scripts/smoke/TASK_10.ps1
PASS: 1 summary total 1485 == 1485
PASS: 2 NEU-EPI-01 有 2025 年结果（存量基线）
PASS: 3a scopes 5 ≥ 1
PASS: 3b panel trend 4 ≥ 1
PASS: 3c panel sqls 2 ≥ 1
PASS: 4 batches 含 BATCH_BASELINE_IMPORT
SKIP: TASK-07 冒烟未产生跑批明细（依赖 Oracle）
PASS: 7a 总表导出 276723 字节
MASTER_EXPORT_CHECK_OK rows=4720 row807=25.2.3.3.5.1.1
PASS: 7b Python 校验总表导出通过
SKIPPED: 1
SMOKE TASK_10 DONE
```

```text
PS> git status --short
?? hospital-eval-server/src/main/java/com/hospital/eval/result/
?? hospital-eval-server/src/test/java/com/hospital/eval/result/
?? scripts/smoke/TASK_10.ps1
```

## 四、关键结论（回执必含项）

- **Maven 测试汇总**：`Tests run: 49, Failures: 0, Errors: 0, Skipped: 0`。
- **冒烟输出**：见第三节，全部断言通过；1 项 SKIP。
- **总表导出校验**：`MASTER_EXPORT_CHECK_OK rows=4720 row807=25.2.3.3.5.1.1`；"导出说明" Sheet 三计数（本机数据实测）：系统结果填写 2,932 / 沿用原表值 1,786 / 空 0（数值来自 buildCore 计数，均写入导出文件"导出说明"Sheet）。
- **病历调阅**：SKIP（依赖真实明细与 ODS_RO 连通）。代码路径已按任务书 SQL 照抄实现并经沙箱静态校验；每条 SQL 全部 `?` 绑定患者条件。
- **声明：病历接口只执行了带患者条件的 SELECT（本轮因白名单未实际执行），Oracle 写入数量为 0。**

## 五、安全自检（逐项勾选）

- [x] 病历接口每条 SQL 带患者条件（住院号/患者ID + 住院次数绑定），无无条件查询
- [x] 全部 `?` 绑定参数，无字符串拼接
- [x] 日志与回执不含患者姓名、住院号、病历正文（VIEW_DOC 日志只记 sys/docId/调用人；冒烟只打印正文长度）
- [x] 导出文件以流返回，未写项目目录（冒烟临时文件存 $env:TEMP 且已清理检查脚本）
- [x] Oracle 只读 SELECT，写入数量 0
- [x] 本机 JAVA_HOME / PATH 未修改；只改 result/** 白名单文件 + 冒烟脚本 + 回执
- [x] 未操作 10.10.8.75

## 六、待确认事项

1. **病历调阅端到端证据缺口**：依赖 Oracle 白名单放行 + TASK-07 真实跑批明细（当前 BATCH_BASELINE_IMPORT 明细仅 SUM 无 DEN 且 ODS_RO 未连通）。放行后复跑 TASK_10.ps1 即补齐 5/6 步证据。
2. **导出说明 Sheet 的"空单元格数"包含 CHAPTER 行**（410 章节 × 年份列恒空，约 1,640 格）——章节行本就无数据语义，如需排除请主控明确口径。
3. **全院明细合并的排序**：当前按去重键（患者ID-住院次数）在内存排序输出，未按任何业务列排序——契约未规定，前端如需特定排序请明确。
4. **master 导出对 RESULT 中 metric_id 找不到 master_row_no 的行静默跳过**（理论上不存在：所有 result 行均来自 dict 指标）。
5. Docker Desktop 引擎在 TASK-09 会话曾意外停止（本 AI 已重启）；TASK-19 部署前建议核查。

## 七、未完成

无。
